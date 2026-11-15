package coolmsg.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import coolmsg.common.FormatException;
import coolmsg.common.ProtocolInfo;
import coolmsg.common.file.TimeFormat;
import coolmsg.common.model.MessageData;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;
import coolmsg.server.config.UserDirectory;
import coolmsg.server.service.MessageService;
import coolmsg.server.service.Outbox;
import coolmsg.server.store.LockStripes;
import coolmsg.server.store.MessageStore;
import coolmsg.server.store.PendingIndex;
import coolmsg.server.store.StoredMessage;

/**
 * 구현 4단계 테스트: 메시지 저장소·미전달 색인·메시지 서비스 (설계서 3.8, U5·U14~U16·U35·U36).
 *
 * 소켓 없이 확인한다. 보내기는 가짜 Outbox(보낸 패킷을 모아 두기만 함)로 바꿔 끼운다.
 * ServerTest.main에서 불린다.
 */
final class MessageTest {

    private interface Action {
        void run() throws Exception;
    }

    private static BiConsumer<String, Boolean> check;

    private MessageTest() {
    }

    static void run(BiConsumer<String, Boolean> checker, Consumer<String> section) throws Exception {
        check = checker;

        section.accept("LockStripes - 같은 키는 같은 잠금");
        lockStripes();

        section.accept("MessageStore - 저장·중복·수신 기록 (3.8, U35)");
        messageStore();

        section.accept("PendingIndex - 추가·삭제·정상 종료 표시·다시 만들기 (U14, U15)");
        pendingIndex();

        section.accept("MessageService - SEND 검사 (U5, U35)");
        sendValidation();

        section.accept("MessageService - 보내기·재전송·ACK·밀린 메시지 (3.8.5)");
        sendAckPending();

        section.accept("MessageService - 저장소 열기·닫기 (3.8.6)");
        openClose();
    }

    // ===================================================================

    private static void lockStripes() {
        LockStripes s = new LockStripes();
        check.accept("같은 키 → 같은 잠금 객체", s.lockFor("abc") == s.lockFor(new String("abc")));
        Set<Object> distinct = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            distinct.add(s.lockFor(UUID.randomUUID().toString()));
        }
        check.accept("메시지 ID 1000개 → 잠금은 64개를 넘지 않고, 고루 퍼짐(60개 이상 사용)",
                distinct.size() <= LockStripes.DEFAULT_SIZE && distinct.size() >= 60);
    }

    private static void messageStore() throws Exception {
        Path dir = Files.createTempDirectory("coolmsg-store");
        MessageStore store = new MessageStore(dir);

        check.accept("UUID 형식만 유효 (U35)", MessageStore.isValidMessageId(UUID.randomUUID().toString())
                && !MessageStore.isValidMessageId("../../config/users")
                && !MessageStore.isValidMessageId("3F2A9C1E-8B4D-4C1A-9E2F-1A2B3C4D5E6F")
                && !MessageStore.isValidMessageId(null));

        MessageData d = message("kim", List.of("lee", "park"), List.of("choi"));
        MessageStore.CreateResult r1 = store.create(d);
        check.accept("새 메시지 저장 → created", r1.isCreated() && Files.exists(dir.resolve(d.getMessageId() + ".txt")));
        String text = Files.readString(dir.resolve(d.getMessageId() + ".txt"), StandardCharsets.UTF_8);
        check.accept("받는 사람·참조 모두 delivered=false로 시작",
                text.contains("delivered.lee=false") && text.contains("delivered.park=false")
                        && text.contains("delivered.choi=false"));

        MessageData again = new MessageData(d.getMessageId(), List.of("lee"), List.of(), "다른 내용");
        again.setSender("kim");
        again.setSentAt(d.getSentAt().plusMinutes(5));
        MessageStore.CreateResult r2 = store.create(again);
        check.accept("같은 ID로 다시 저장 → 저장하지 않고 기존 메시지(처음 sentAt)를 돌려줌",
                !r2.isCreated() && r2.getMessage().getData().getSentAt().equals(d.getSentAt())
                        && r2.getMessage().getData().getReceivers().equals(List.of("lee", "park")));

        MessageData bad = new MessageData("../evil", List.of("lee"), List.of(), "x");
        expectThrows("UUID가 아닌 ID로 저장 → FormatException (경로 조작 방지)", FormatException.class,
                () -> store.create(bad));
        check.accept("없는 메시지 load → null", store.load(UUID.randomUUID().toString()) == null);

        String id = d.getMessageId();
        check.accept("ACK 기록: 처음 → CHANGED", store.markDelivered(id, "lee") == MessageStore.DeliveryUpdate.CHANGED);
        check.accept("ACK 기록: 두 번째 → ALREADY", store.markDelivered(id, "lee") == MessageStore.DeliveryUpdate.ALREADY);
        check.accept("받는 사람이 아닌 사람의 ACK → NOT_RECIPIENT",
                store.markDelivered(id, "kim") == MessageStore.DeliveryUpdate.NOT_RECIPIENT);
        check.accept("참조 대상의 ACK도 기록됨", store.markDelivered(id, "choi") == MessageStore.DeliveryUpdate.CHANGED);
        StoredMessage loaded = store.load(id);
        check.accept("파일에 delivered가 남음", loaded.isDelivered("lee") && loaded.isDelivered("choi")
                && !loaded.isDelivered("park"));

        Files.writeString(dir.resolve("memo.txt"), "관계없는 파일");
        check.accept("listIds는 UUID 이름의 파일만", store.listIds().equals(List.of(id)));
    }

    private static void pendingIndex() throws Exception {
        Path root = Files.createTempDirectory("coolmsg-pending");
        PendingIndex index = new PendingIndex(root.resolve("pending"));
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();

        check.accept("색인이 없으면 빈 목록", index.list("lee").isEmpty());
        index.add("lee", a);
        index.add("lee", b);
        index.add("lee", a);
        check.accept("추가한 순서대로, 중복 제거", index.list("lee").equals(List.of(a, b)));
        index.remove("lee", a);
        check.accept("ACK → 그 ID만 지움", index.list("lee").equals(List.of(b)));
        Files.writeString(root.resolve("pending/lee.txt"), b + "\n3f2a9c1e-8b4d", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        check.accept("반쯤 쓴 줄은 무시", index.list("lee").equals(List.of(b)));

        check.accept("정상 종료 표시가 없으면 false", !index.consumeCleanMarker());
        index.writeCleanMarker();
        check.accept("표시를 만든 뒤 → true, 그리고 지워짐", index.consumeCleanMarker() && !index.consumeCleanMarker());

        // 다시 만들기: 메시지 파일 기준
        MessageStore store = new MessageStore(root.resolve("messages"));
        MessageData later = message("kim", List.of("lee", "park"), List.of());
        later.setSentAt(LocalDateTime.of(2026, 11, 20, 10, 0, 0));
        MessageData earlier = message("kim", List.of("lee"), List.of("choi"));
        earlier.setSentAt(LocalDateTime.of(2026, 11, 20, 9, 0, 0));
        store.create(later);
        store.create(earlier);
        store.markDelivered(later.getMessageId(), "park");
        Files.writeString(root.resolve("messages/" + UUID.randomUUID() + ".txt"), "손상된 파일 (빈 줄 없음)");
        index.add("ghost", UUID.randomUUID().toString()); // 메시지 파일에 없는 색인 → 지워져야 함

        List<String> problems = new ArrayList<>();
        int count = index.rebuild(store, problems);
        check.accept("다시 만들기: 미전달 3건 (lee 2, choi 1, park은 이미 받음)", count == 3);
        check.accept("다시 만들기: 보낸 시각 순서",
                index.list("lee").equals(List.of(earlier.getMessageId(), later.getMessageId())));
        check.accept("다시 만들기: 참조 대상 포함, 받은 사람은 제외",
                index.list("choi").equals(List.of(earlier.getMessageId())) && index.list("park").isEmpty());
        check.accept("다시 만들기: 메시지 파일에 없는 색인은 사라짐", index.list("ghost").isEmpty());
        check.accept("다시 만들기: 손상된 파일은 건너뛰고 기록", problems.size() == 1);
    }

    // ===================================================================

    private static void sendValidation() throws Exception {
        Env env = new Env();
        env.service.openStorage();

        check.accept("UUID가 아닌 messageId → SEND_FAIL (U35)",
                env.failReason(send("../../x", "lee", "", "안녕")).equals(MessageService.BAD_MESSAGE_ID));
        check.accept("받는 사람 없음 → SEND_FAIL",
                env.failReason(send(newId(), "", "", "안녕")).equals(MessageService.NO_RECEIVER));
        check.accept("참조만 있음 → SEND_FAIL (받는 사람 1명 이상)",
                env.failReason(send(newId(), "", "lee", "안녕")).equals(MessageService.NO_RECEIVER));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < ProtocolInfo.MAX_RECIPIENTS; i++) {
            many.add("u" + i);
        }
        check.accept("받는 사람+참조 101명 → SEND_FAIL",
                env.failReason(send(newId(), String.join(",", many), "lee", "안녕"))
                        .equals(MessageService.TOO_MANY_RECIPIENTS));
        check.accept("없는 아이디 → SEND_FAIL (누가 없는지 안내)",
                env.failReason(send(newId(), "lee,nobody", "", "안녕")).equals(MessageService.UNKNOWN_USER + "nobody"));
        check.accept("삭제된 아이디 → SEND_FAIL",
                env.failReason(send(newId(), "park", "", "안녕")).equals(MessageService.UNKNOWN_USER + "park"));
        check.accept("빈 본문 → SEND_FAIL", env.failReason(send(newId(), "lee", "", "")).equals(MessageService.EMPTY_BODY));
        check.accept("태그·공백뿐인 본문(<br> &nbsp;) → SEND_FAIL",
                env.failReason(send(newId(), "lee", "", "<br>\n &nbsp; <p></p>")).equals(MessageService.EMPTY_BODY));
        check.accept("본문 1MB 초과 → SEND_FAIL",
                env.failReason(send(newId(), "lee", "", "가".repeat(ProtocolInfo.MAX_BODY_BYTES / 3 + 1)))
                        .equals(MessageService.BODY_TOO_LARGE));
        check.accept("첨부파일이 있는 SEND → SEND_FAIL (제출 범위 밖)",
                env.failReason(send(newId(), "lee", "", "안녕").put("attachment.0", newId()))
                        .equals(MessageService.NO_ATTACHMENT));
        check.accept("검사에 걸리면 아무것도 저장하지 않음", env.store.listIds().isEmpty());
        check.accept("본문 정확히 1MB(UTF-8)는 허용",
                env.reply(send(newId(), "lee", "", "a".repeat(ProtocolInfo.MAX_BODY_BYTES))).getType()
                        == PacketType.SEND_OK);
    }

    private static void sendAckPending() throws Exception {
        Env env = new Env();
        env.service.openStorage();
        env.outbox.online.add("kim");

        // 오프라인 lee, 중복 아이디 정리
        String id1 = newId();
        Packet ok = env.reply(send(id1, "lee,lee,choi", "lee,kim", "첫 메시지").setReqId(5));
        check.accept("SEND_OK: reqId·messageId·sentAt",
                ok.getType() == PacketType.SEND_OK && ok.getReqId() == 5 && id1.equals(ok.get(HeaderKeys.MESSAGE_ID))
                        && ok.get(HeaderKeys.SENT_AT) != null);
        StoredMessage m1 = env.store.load(id1);
        check.accept("받는 사람 중복 합침, 받는 사람이면서 참조면 받는 사람으로 한 번만",
                m1.getData().getReceivers().equals(List.of("lee", "choi")) && m1.getData().getCc().equals(List.of("kim")));
        check.accept("보낸 사람·보낸 시각은 서버가 채움",
                "kim".equals(m1.getData().getSender()) && TimeFormat.format(m1.getData().getSentAt())
                        .equals(ok.get(HeaderKeys.SENT_AT)));
        check.accept("받는 사람·참조마다 색인 추가",
                env.index.list("lee").equals(List.of(id1)) && env.index.list("choi").equals(List.of(id1))
                        && env.index.list("kim").equals(List.of(id1)));
        check.accept("접속 중인 참조 대상(kim)에게만 바로 MSG, 오프라인 lee에게는 없음",
                env.outbox.sentTo("kim").size() == 1 && env.outbox.sentTo("lee").isEmpty());
        Packet msg = env.outbox.sentTo("kim").get(0);
        check.accept("MSG: 보낸 사람·받는 사람·sentAt·본문, 서버 전용 delivered.* 없음",
                msg.getType() == PacketType.MSG && "kim".equals(msg.get("sender"))
                        && "lee,choi".equals(msg.get("receivers")) && "첫 메시지".equals(msg.getText())
                        && !msg.has("delivered.lee"));
        check.accept("순서: SEND_OK가 MSG보다 먼저 (3.8.5 ①)", env.order.indexOf("reply") < env.order.indexOf("kim"));

        // 재전송
        env.outbox.clear();
        Packet again = env.reply(send(id1, "lee", "", "재전송").setReqId(6));
        check.accept("같은 사람이 같은 ID로 재전송 → SEND_OK (처음 sentAt), 다시 저장·전달하지 않음",
                again.getType() == PacketType.SEND_OK && again.get(HeaderKeys.SENT_AT).equals(ok.get(HeaderKeys.SENT_AT))
                        && env.outbox.sentTo("kim").isEmpty()
                        && "첫 메시지".equals(env.store.load(id1).getData().getBody()));
        Packet stolen = env.replyAs("lee", send(id1, "kim", "", "가로채기").setReqId(7));
        check.accept("다른 사람이 같은 ID로 보냄 → SEND_FAIL '이미 사용된 메시지 ID' (U35)",
                stolen.getType() == PacketType.SEND_FAIL
                        && MessageService.USED_MESSAGE_ID.equals(stolen.get(HeaderKeys.REASON)));

        // 재전송 때 빠진 색인 복구 (앞선 시도에서 색인 쓰기가 실패한 경우)
        env.index.remove("choi", id1);
        env.reply(send(id1, "lee", "", "재전송"));
        check.accept("재전송 때 안 받았는데 색인에 없는 사람은 다시 넣음", env.index.list("choi").equals(List.of(id1)));

        // 밀린 메시지
        String id2 = newId();
        env.reply(send(id2, "lee", "", "두 번째"));
        env.outbox.online.add("lee");
        int sent = env.service.deliverPending("lee");
        List<Packet> toLee = env.outbox.sentTo("lee");
        check.accept("로그인 때 밀린 메시지를 저장 순서대로 보냄",
                sent == 2 && toLee.size() == 2 && id1.equals(toLee.get(0).get(HeaderKeys.MESSAGE_ID))
                        && id2.equals(toLee.get(1).get(HeaderKeys.MESSAGE_ID)));
        check.accept("밀린 메시지는 대기열 여유를 보며 보냄 (U36)", env.outbox.paced == 2);

        // ACK
        env.service.ack("lee", new Packet(PacketType.ACK).put(HeaderKeys.MESSAGE_ID, id1));
        check.accept("ACK → 메시지 파일 delivered=true, 색인에서 빠짐",
                env.store.load(id1).isDelivered("lee") && env.index.list("lee").equals(List.of(id2)));
        env.service.ack("park", new Packet(PacketType.ACK).put(HeaderKeys.MESSAGE_ID, id2));
        check.accept("받는 사람이 아닌 사람의 ACK는 무시", !env.store.load(id2).isDelivered("park"));
        env.service.ack("lee", new Packet(PacketType.ACK).put(HeaderKeys.MESSAGE_ID, "../../x"));
        check.accept("이상한 ID의 ACK도 오류 없이 무시", env.index.list("lee").equals(List.of(id2)));
        env.outbox.clear();
        check.accept("ACK한 메시지는 다음 로그인 때 다시 오지 않음", env.service.deliverPending("lee") == 1);

        // 색인과 메시지 파일이 어긋난 경우
        String ghost = newId();
        env.index.add("lee", ghost);
        env.service.ack("lee", new Packet(PacketType.ACK).put(HeaderKeys.MESSAGE_ID, id2));
        env.index.add("lee", id2); // 이미 받은 메시지가 색인에 남은 경우
        check.accept("색인에만 있는 ID·이미 받은 메시지는 보내지 않고 색인에서 정리",
                env.service.deliverPending("lee") == 0 && env.index.list("lee").isEmpty());

        // 나에게 보내기
        env.outbox.clear();
        String self = newId();
        env.reply(send(self, "kim", "", "메모"));
        check.accept("나에게 보내기 → 내 연결로 MSG", env.outbox.sentTo("kim").size() == 1);
        env.outbox.online.remove("lee");
        env.outbox.clear();
        check.accept("연결이 끊기면 밀린 메시지 보내기를 멈춤", env.service.deliverPending("choi") == 0);
    }

    private static void openClose() throws Exception {
        Env env = new Env();
        Packet before = env.reply(send(newId(), "lee", "", "안녕"));
        check.accept("저장소를 열기 전(서버 중지) SEND → SEND_FAIL",
                before.getType() == PacketType.SEND_FAIL && MessageService.STOPPED.equals(before.get(HeaderKeys.REASON)));

        env.service.openStorage();
        check.accept("처음 시작(표시 없음) → 색인 다시 만들기", env.logs.stream().anyMatch(l -> l.contains("다시 만들기")));
        String id = newId();
        env.reply(send(id, "lee", "", "안녕"));
        env.service.closeStorage();
        check.accept("중지 → 정상 종료 표시(.clean) 생성", Files.exists(env.root.resolve("pending/.clean")));
        Packet after = env.reply(send(newId(), "lee", "", "안녕"));
        check.accept("중지 뒤 SEND → SEND_FAIL", after.getType() == PacketType.SEND_FAIL);

        env.logs.clear();
        env.service.openStorage();
        check.accept("정상 종료 뒤 시작 → 색인 그대로 사용, 표시 지움",
                env.logs.stream().anyMatch(l -> l.contains("그대로")) && !Files.exists(env.root.resolve("pending/.clean")));
        check.accept("색인 유지", env.index.list("lee").equals(List.of(id)));

        // 비정상 종료 흉내: 표시 없이 색인 파일이 사라짐
        Files.delete(env.root.resolve("pending/lee.txt"));
        Env restarted = new Env(env.root);
        restarted.service.openStorage();
        check.accept("비정상 종료 뒤 시작 → 메시지 파일로 색인 복구", restarted.index.list("lee").equals(List.of(id)));
    }

    // ===================================================================
    // 도우미
    // ===================================================================

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static MessageData message(String sender, List<String> to, List<String> cc) {
        MessageData d = new MessageData(newId(), to, cc, "본문");
        d.setSender(sender);
        d.setSentAt(TimeFormat.now());
        return d;
    }

    private static Packet send(String messageId, String receivers, String cc, String body) {
        Packet p = new Packet(PacketType.SEND).put(HeaderKeys.MESSAGE_ID, messageId).put("receivers", receivers);
        if (!cc.isEmpty()) {
            p.put("cc", cc);
        }
        return p.setText(body);
    }

    private static void expectThrows(String name, Class<? extends Throwable> type, Action action) {
        try {
            action.run();
            check.accept(name, false);
        } catch (Throwable t) {
            check.accept(name, type.isInstance(t));
        }
    }

    /** 임시 폴더 위의 저장소·색인·서비스와 가짜 Outbox. kim, lee, choi와 삭제된 park. */
    private static final class Env implements ServerEventListener {
        final Path root;
        final MessageStore store;
        final PendingIndex index;
        final FakeOutbox outbox;
        final MessageService service;
        final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        /** 응답과 MSG가 나간 순서 ("reply" 또는 받는 사람 아이디). */
        final List<String> order;

        Env() throws IOException {
            this(Files.createTempDirectory("coolmsg-msg"));
        }

        Env(Path root) throws IOException {
            this.root = root;
            this.order = Collections.synchronizedList(new ArrayList<>());
            this.outbox = new FakeOutbox(order);
            this.store = new MessageStore(root.resolve("messages"));
            this.index = new PendingIndex(root.resolve("pending"));
            this.service = new MessageService(store, index, users(), outbox, this);
        }

        Packet reply(Packet request) {
            return replyAs("kim", request);
        }

        Packet replyAs(String sender, Packet request) {
            List<Packet> replies = new ArrayList<>();
            service.send(sender, request, p -> {
                order.add("reply");
                replies.add(p);
            });
            return replies.size() == 1 ? replies.get(0) : null;
        }

        String failReason(Packet request) {
            Packet r = reply(request);
            return (r != null && r.getType() == PacketType.SEND_FAIL) ? r.get(HeaderKeys.REASON) : "(SEND_FAIL 아님)";
        }

        @Override
        public void onLog(String message) {
            logs.add(message);
        }

        @Override
        public void onServerStateChanged(boolean running) {
        }

        private static UserDirectory users() throws IOException {
            Path dir = Files.createTempDirectory("coolmsg-msg-users");
            UserDirectory d = new UserDirectory(dir.resolve("users.txt"));
            d.load();
            try {
                d.add("kim", "김철수", "pass".toCharArray());
                d.add("lee", "이영희", "pass".toCharArray());
                d.add("park", "박민수", "pass".toCharArray());
                d.add("choi", "최지우", "pass".toCharArray());
                d.delete("park");
            } catch (Exception e) {
                throw new IOException(e);
            }
            return d;
        }
    }

    /** 보낸 패킷을 사람별로 모아 두기만 하는 가짜 Outbox. */
    private static final class FakeOutbox implements Outbox {
        final Set<String> online = Collections.synchronizedSet(new HashSet<>());
        private final Map<String, List<Packet>> sent = new HashMap<>();
        private final List<String> order;
        int paced;

        FakeOutbox(List<String> order) {
            this.order = order;
        }

        @Override
        public boolean isOnline(String userId) {
            return online.contains(userId);
        }

        @Override
        public synchronized boolean send(String userId, Packet packet) {
            if (!online.contains(userId)) {
                return false;
            }
            sent.computeIfAbsent(userId, k -> new ArrayList<>()).add(packet);
            order.add(userId);
            return true;
        }

        @Override
        public synchronized boolean sendWhenRoom(String userId, Packet packet) {
            boolean ok = send(userId, packet);
            if (ok) {
                paced++;
            }
            return ok;
        }

        synchronized List<Packet> sentTo(String userId) {
            return new ArrayList<>(sent.getOrDefault(userId, List.of()));
        }

        synchronized void clear() {
            sent.clear();
            order.clear();
            paced = 0;
        }
    }
}
