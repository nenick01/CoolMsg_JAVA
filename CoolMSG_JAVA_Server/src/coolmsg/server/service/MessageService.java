package coolmsg.server.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import coolmsg.common.FormatException;
import coolmsg.common.ProtocolInfo;
import coolmsg.common.file.TimeFormat;
import coolmsg.common.model.HtmlText;
import coolmsg.common.model.MessageData;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;
import coolmsg.server.ServerEventListener;
import coolmsg.server.config.UserAccount;
import coolmsg.server.config.UserDirectory;
import coolmsg.server.store.MessageStore;
import coolmsg.server.store.PendingIndex;
import coolmsg.server.store.StoredMessage;

/**
 * 메시지 보내기·수신 확인·밀린 메시지 전달 업무 규칙 (설계서 3.8.5, 5장 U5·U35·U36).
 *
 * 소켓을 모른다. 보내는 일은 Outbox에 부탁한다.
 *
 * 저장소 열기·닫기 (3.8.6):
 *   [서버 시작] openStorage(): .clean 표시가 있으면 색인을 믿고, 없으면 메시지 파일로 색인을 다시 만든다.
 *   [서버 중지] closeStorage(): 처리 중인 보내기·ACK가 끝나기를 기다린 뒤 .clean 표시를 만든다.
 *   보내기·ACK는 읽기 잠금, 열기·닫기는 쓰기 잠금을 잡는다. 그래서 "저장은 됐는데 색인은 안 쓴" 순간에
 *   .clean이 만들어지는 일이 없다. (잠금 안에서는 기다리는 일을 하지 않는다. 대기열에 넣기는 바로 돌아온다.)
 */
public class MessageService {

    public static final String STOPPED = "서버가 중지 중입니다.";
    public static final String BAD_MESSAGE_ID = "메시지 ID 형식이 올바르지 않습니다.";
    public static final String USED_MESSAGE_ID = "이미 사용된 메시지 ID입니다.";
    public static final String NO_RECEIVER = "받는 사람을 한 명 이상 지정해 주세요.";
    public static final String TOO_MANY_RECIPIENTS =
            "받는 사람과 참조는 합쳐서 " + ProtocolInfo.MAX_RECIPIENTS + "명까지 보낼 수 있습니다.";
    public static final String UNKNOWN_USER = "없는 사용자입니다: ";
    public static final String EMPTY_BODY = "본문이 비어 있습니다.";
    public static final String BODY_TOO_LARGE = "본문이 너무 깁니다 (1MB 이하).";
    public static final String NO_ATTACHMENT = "첨부파일은 아직 지원하지 않습니다.";
    public static final String SAVE_FAILED = "서버에 저장하지 못했습니다. 잠시 후 다시 보내 주세요.";

    private final MessageStore store;
    private final PendingIndex index;
    private final UserDirectory users;
    private final Outbox outbox;
    private final ServerEventListener events;

    private final ReentrantReadWriteLock storageLock = new ReentrantReadWriteLock();
    private boolean open; // storageLock 안에서만 읽고 쓴다

    public MessageService(MessageStore store, PendingIndex index, UserDirectory users, Outbox outbox,
            ServerEventListener events) {
        this.store = store;
        this.index = index;
        this.users = users;
        this.outbox = outbox;
        this.events = events;
    }

    // =====================================================================
    // 저장소 열기·닫기 (3.8.6)
    // =====================================================================

    /** [서버 시작] 때, 접속을 받기 전에 부른다. */
    public void openStorage() throws IOException {
        storageLock.writeLock().lock();
        try {
            if (open) {
                return;
            }
            if (index.consumeCleanMarker()) {
                events.onLog("미전달 색인: 지난번 정상 종료 → 그대로 사용");
            } else {
                List<String> problems = new ArrayList<>();
                int count = index.rebuild(store, problems);
                for (String p : problems) {
                    events.onLog("경고: " + p);
                }
                events.onLog("미전달 색인 다시 만들기 완료 (미전달 " + count + "건)");
            }
            open = true;
        } finally {
            storageLock.writeLock().unlock();
        }
    }

    /** [서버 중지] 때, 모든 연결을 끊은 뒤 부른다. 처리 중인 작업이 끝나기를 기다린다. */
    public void closeStorage() {
        storageLock.writeLock().lock();
        try {
            if (!open) {
                return;
            }
            open = false;
            index.writeCleanMarker();
            events.onLog("미전달 색인 저장 완료 (정상 종료 표시)");
        } catch (IOException e) {
            events.onLog("경고: 정상 종료 표시를 만들지 못했습니다. 다음 시작 때 색인을 다시 만듭니다. (" + e.getMessage() + ")");
        } finally {
            storageLock.writeLock().unlock();
        }
    }

    // =====================================================================
    // ① 보내기 (SEND)
    // =====================================================================

    /**
     * SEND 처리. 응답(SEND_OK/SEND_FAIL)을 reply로 먼저 보내고, 그다음 접속 중인 받는 사람에게 MSG를 보낸다(3.8.5 ① 4→5).
     *
     * @param sender 로그인한 보낸 사람 아이디
     * @param reply  보낸 사람에게 응답을 보내는 함수 (보내기 대기열에 넣기)
     */
    public void send(String sender, Packet request, Consumer<Packet> reply) {
        StoredMessage toDeliver = null;
        storageLock.readLock().lock();
        try {
            if (!open) {
                reply.accept(fail(request, STOPPED));
                return;
            }
            MessageData data;
            try {
                data = validate(sender, request);
            } catch (SendRejected e) {
                reply.accept(fail(request, e.getMessage()));
                return;
            }
            MessageStore.CreateResult result = store.create(data);
            StoredMessage saved = result.getMessage();
            if (!result.isCreated() && !sender.equals(saved.getData().getSender())) {
                events.onLog("보내기 거부 (" + sender + "): 다른 사람이 쓴 메시지 ID " + data.getMessageId());
                reply.accept(fail(request, USED_MESSAGE_ID)); // U35
                return;
            }
            addToIndex(saved, !result.isCreated());
            reply.accept(Packet.replyTo(request, PacketType.SEND_OK)
                    .put(HeaderKeys.MESSAGE_ID, saved.getData().getMessageId())
                    .put(HeaderKeys.SENT_AT, TimeFormat.format(saved.getData().getSentAt())));
            if (result.isCreated()) {
                events.onLog("메시지 저장: " + describe(saved.getData()));
                toDeliver = saved;
            } else {
                events.onLog("재전송 (이미 저장됨): " + sender + " " + data.getMessageId());
            }
        } catch (IOException e) {
            events.onLog("메시지 저장 실패 (" + sender + "): " + e.getMessage());
            reply.accept(fail(request, SAVE_FAILED));
        } finally {
            storageLock.readLock().unlock();
        }
        if (toDeliver != null) {
            deliver(toDeliver); // C2: 저장 먼저, 접속 확인 나중
        }
    }

    /** 받는 사람·참조마다 색인에 추가. 재전송이면 아직 안 받았는데 색인에 없는 사람만 다시 넣는다(앞선 시도가 중간에 실패한 경우 복구). */
    private void addToIndex(StoredMessage m, boolean resend) throws IOException {
        String id = m.getData().getMessageId();
        for (String userId : m.recipients()) {
            if (resend && (m.isDelivered(userId) || index.list(userId).contains(id))) {
                continue;
            }
            index.add(userId, id);
        }
    }

    /** 접속 중인 받는 사람·참조에게 MSG. 접속 중이 아니면 다음 로그인 때 색인으로 받는다. */
    private void deliver(StoredMessage m) {
        Packet msg = toMsg(m);
        for (String userId : m.recipients()) {
            outbox.send(userId, msg);
        }
    }

    /** SEND 검사 (U5, U35). 통과하면 서버가 보낸 사람·시각을 채운 메시지를 돌려준다. */
    private MessageData validate(String sender, Packet request) throws SendRejected {
        String messageId = request.get(HeaderKeys.MESSAGE_ID);
        if (!MessageStore.isValidMessageId(messageId)) {
            throw new SendRejected(BAD_MESSAGE_ID);
        }
        if (request.has(MessageData.KEY_ATTACHMENT_PREFIX + 0)) {
            throw new SendRejected(NO_ATTACHMENT); // 제출 범위에서는 뼈대만 (1.3)
        }
        Set<String> receivers = new LinkedHashSet<>(request.getList(MessageData.KEY_RECEIVERS));
        Set<String> cc = new LinkedHashSet<>(request.getList(MessageData.KEY_CC));
        cc.removeAll(receivers); // 받는 사람이면서 참조인 사람은 받는 사람으로 한 번만
        if (receivers.isEmpty()) {
            throw new SendRejected(NO_RECEIVER);
        }
        if (receivers.size() + cc.size() > ProtocolInfo.MAX_RECIPIENTS) {
            throw new SendRejected(TOO_MANY_RECIPIENTS);
        }
        List<String> unknown = new ArrayList<>();
        for (String id : concat(receivers, cc)) {
            UserAccount account = users.find(id);
            if (account == null || account.isDeleted()) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            throw new SendRejected(UNKNOWN_USER + String.join(", ", unknown));
        }
        if (request.getDataLength() > ProtocolInfo.MAX_BODY_BYTES) {
            throw new SendRejected(BODY_TOO_LARGE);
        }
        String body = request.getText();
        if (HtmlText.isBlank(body)) {
            throw new SendRejected(EMPTY_BODY);
        }
        MessageData data = new MessageData(messageId, new ArrayList<>(receivers), new ArrayList<>(cc), body);
        data.setSender(sender);
        data.setSentAt(TimeFormat.now());
        data.setReplyTo(request.get(MessageData.KEY_REPLY_TO));
        data.setForwardOf(request.get(MessageData.KEY_FORWARD_OF));
        return data;
    }

    // =====================================================================
    // ③ 수신 확인 (ACK) — 응답 없음
    // =====================================================================

    public void ack(String userId, Packet request) {
        String messageId = request.get(HeaderKeys.MESSAGE_ID);
        storageLock.readLock().lock();
        try {
            if (!open) {
                return; // ACK를 못 남겨도 다음 로그인 때 다시 보내고, 클라이언트가 중복으로 거른다
            }
            MessageStore.DeliveryUpdate update = store.markDelivered(messageId, userId);
            if (update != MessageStore.DeliveryUpdate.NOT_RECIPIENT) {
                index.remove(userId, messageId);
            }
            // 5단계: update == CHANGED 이면 보낸 사람에게 RECEIPT(DELIVERED)
        } catch (IOException e) {
            events.onLog("수신 확인 기록 실패 (" + userId + ", " + messageId + "): " + e.getMessage());
        } finally {
            storageLock.readLock().unlock();
        }
    }

    // =====================================================================
    // ② 로그인 때 밀린 메시지 보내기 (U36)
    // =====================================================================

    /**
     * 밀린 메시지를 별도 스레드에서 보낸다. LOGIN_OK를 대기열에 넣은 뒤에 부른다.
     * 보내기 대기열이 절반 이상 차 있으면 줄어들 때까지 기다린다(대기열 가득으로 끊기지 않게).
     */
    public void deliverPendingAsync(String userId) {
        Thread t = new Thread(() -> deliverPending(userId), "pending-" + userId);
        t.setDaemon(true);
        t.start();
    }

    /** 밀린 메시지 보내기 (테스트에서는 직접 부를 수 있다). 보낸 개수를 돌려준다. */
    public int deliverPending(String userId) {
        int sent = 0;
        try {
            for (String id : index.list(userId)) {
                StoredMessage m;
                try {
                    m = store.load(id);
                } catch (FormatException e) {
                    events.onLog("경고: 손상된 메시지 파일을 색인에서 뺍니다: " + id + " (" + e.getMessage() + ")");
                    index.remove(userId, id);
                    continue;
                }
                if (m == null || !m.isRecipient(userId) || m.isDelivered(userId)) {
                    index.remove(userId, id); // 색인이 메시지 파일과 어긋남 → 메시지 파일 기준으로 맞춘다
                    continue;
                }
                if (!outbox.sendWhenRoom(userId, toMsg(m))) {
                    break; // 연결이 끊김. 남은 것은 다음 로그인 때
                }
                sent++;
            }
        } catch (IOException e) {
            events.onLog("밀린 메시지 보내기 실패 (" + userId + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (sent > 0) {
            events.onLog("밀린 메시지 " + sent + "개 보냄: " + userId);
        }
        return sent;
    }

    // =====================================================================

    /** 저장된 메시지 → MSG 패킷 (서버 전용 키 delivered.*는 넣지 않는다). */
    static Packet toMsg(StoredMessage m) {
        return new Packet(PacketType.MSG).putAll(m.getData().toHeader()).setText(m.getData().getBody());
    }

    private static Packet fail(Packet request, String reason) {
        return Packet.replyTo(request, PacketType.SEND_FAIL).put(HeaderKeys.REASON, reason);
    }

    /** 로그용: "kim → lee, park (참조 choi)" */
    private static String describe(MessageData d) {
        String s = d.getSender() + " → " + String.join(", ", d.getReceivers());
        return d.getCc().isEmpty() ? s : s + " (참조 " + String.join(", ", d.getCc()) + ")";
    }

    private static List<String> concat(Set<String> a, Set<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** SEND 검사에 걸림. 메시지는 사용자에게 보여 줄 문장. */
    private static final class SendRejected extends Exception {
        private static final long serialVersionUID = 1L;

        SendRejected(String reason) {
            super(reason);
        }
    }
}
