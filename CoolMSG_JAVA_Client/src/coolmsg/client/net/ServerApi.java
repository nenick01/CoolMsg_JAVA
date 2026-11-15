package coolmsg.client.net;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import coolmsg.client.model.UserEntry;
import coolmsg.common.FormatException;
import coolmsg.common.ProtocolInfo;
import coolmsg.common.file.TimeFormat;
import coolmsg.common.model.MessageData;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;

/**
 * 서버에 요청하는 메서드 모음 (설계서 4.1).
 *
 * 화면 코드는 패킷 형식을 몰라도 login(), logout() 같은 메서드만 부르면 된다.
 * 응답을 기다리는 메서드는 오래 걸릴 수 있으므로 Swing 스레드가 아닌 곳(SwingWorker)에서 불러야 한다.
 */
public class ServerApi {

    public static final String TIMEOUT_MESSAGE = "서버 응답이 10초 안에 오지 않았습니다.";

    private final Connection connection;

    public ServerApi(Connection connection) {
        this.connection = connection;
    }

    /** 로그인 결과. 성공이면 사용자 목록, 실패면 이유. */
    public static final class LoginResult {
        private final boolean ok;
        private final String failReason;
        private final List<UserEntry> users;

        private LoginResult(boolean ok, String failReason, List<UserEntry> users) {
            this.ok = ok;
            this.failReason = failReason;
            this.users = users;
        }

        public boolean isOk() {
            return ok;
        }

        public String getFailReason() {
            return failReason;
        }

        /** 사용자 목록 (등록 순서, 접속 상태 포함). */
        public List<UserEntry> getUsers() {
            return users;
        }
    }

    /**
     * 로그인 요청 (응답을 기다린다).
     *
     * @throws IOException 연결이 끊기거나 10초 안에 응답이 없음
     */
    public LoginResult login(String userId, char[] password) throws IOException {
        Packet request = new Packet(PacketType.LOGIN)
                .put(HeaderKeys.VERSION, ProtocolInfo.VERSION)
                .put(HeaderKeys.USER_ID, userId)
                .put(HeaderKeys.PASSWORD, new String(password));
        Packet reply = await(connection.request(request));
        if (reply.getType() == PacketType.LOGIN_OK) {
            List<UserEntry> users = new ArrayList<>();
            for (String line : reply.getText().split("\n")) {
                UserEntry u = UserEntry.parse(line);
                if (u != null) {
                    users.add(u);
                }
            }
            return new LoginResult(true, null, List.copyOf(users));
        }
        String reason = reply.get(HeaderKeys.REASON);
        return new LoginResult(false, reason == null ? "로그인에 실패했습니다." : reason, List.of());
    }

    /** 보내기 결과. 성공이면 서버가 정한 보낸 시각, 실패면 이유. */
    public static final class SendResult {
        private final boolean ok;
        private final String failReason;
        private final LocalDateTime sentAt;

        private SendResult(boolean ok, String failReason, LocalDateTime sentAt) {
            this.ok = ok;
            this.failReason = failReason;
            this.sentAt = sentAt;
        }

        public boolean isOk() {
            return ok;
        }

        public String getFailReason() {
            return failReason;
        }

        public LocalDateTime getSentAt() {
            return sentAt;
        }
    }

    /**
     * 메시지 보내기 (응답을 기다린다). 본문은 HTML이어야 한다(U37).
     * 응답이 오지 않아 실패했을 때 같은 messageId로 다시 보내면 서버가 중복을 거른다(U10, U35).
     *
     * @throws IOException 연결이 끊기거나 10초 안에 응답이 없음
     */
    public SendResult send(MessageData draft) throws IOException {
        Packet request = new Packet(PacketType.SEND)
                .put(HeaderKeys.MESSAGE_ID, draft.getMessageId())
                .putList(MessageData.KEY_RECEIVERS, draft.getReceivers());
        if (!draft.getCc().isEmpty()) {
            request.putList(MessageData.KEY_CC, draft.getCc());
        }
        if (draft.getReplyTo() != null) {
            request.put(MessageData.KEY_REPLY_TO, draft.getReplyTo());
        }
        if (draft.getForwardOf() != null) {
            request.put(MessageData.KEY_FORWARD_OF, draft.getForwardOf());
        }
        request.setText(draft.getBody());
        Packet reply = await(connection.request(request));
        if (reply.getType() == PacketType.SEND_OK) {
            try {
                return new SendResult(true, null, TimeFormat.parse(reply.require(HeaderKeys.SENT_AT)));
            } catch (FormatException e) {
                throw new IOException("서버 응답 형식이 올바르지 않습니다: " + e.getMessage(), e);
            }
        }
        String reason = reply.get(HeaderKeys.REASON);
        return new SendResult(false, reason == null ? "보내지 못했습니다." : reason, null);
    }

    /** 받은 메시지를 보관함에 저장했다고 알린다 (응답 없음). 저장한 뒤에만 보낸다. */
    public void ack(String messageId) {
        connection.send(new Packet(PacketType.ACK).put(HeaderKeys.MESSAGE_ID, messageId));
    }

    /** 로그아웃을 알린다 (응답 없음). */
    public void logout() {
        connection.send(new Packet(PacketType.LOGOUT));
    }

    /** 하트비트 (응답 PONG은 Connection이 버린다). */
    public void heartbeat() {
        connection.send(new Packet(PacketType.PING));
    }

    private static Packet await(java.util.concurrent.CompletableFuture<Packet> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("요청이 중단되었습니다.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw new IOException(TIMEOUT_MESSAGE, cause);
            }
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(String.valueOf(cause), cause);
        }
    }
}
