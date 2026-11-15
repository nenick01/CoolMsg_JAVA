package coolmsg.server.net;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import coolmsg.common.net.Packet;
import coolmsg.server.ServerEventListener;
import coolmsg.server.service.Outbox;

/**
 * 지금 연결된 세션 목록 (설계서 3.1).
 *
 * - 모든 연결(로그인 전 포함): 접속 수 제한, 서버 중지 때 전체 끊기
 * - "아이디 → 지금 연결": 로그인한 사용자. 같은 아이디는 한 연결만
 *
 * 동시성 규칙:
 *   C3 — 옛 연결의 정리 코드가 늦게 실행돼도 새 연결을 지우지 않도록, 아이디 목록에서 뺄 때는
 *        "그 아이디의 지금 연결이 바로 나일 때만" 뺀다(아이디가 아니라 연결 객체로 비교).
 *   C5 — 로그인 등록과 LOGIN_OK의 접속자 목록 만들기를 한 잠금 안에서 한다.
 *   잠금 안에서는 다른 객체에 알리거나(화면) 보내지 않는다. 바뀐 결과는 잠금을 푼 뒤 알린다.
 *
 * 서비스(MessageService)가 "이 사람에게 보내 달라"고 할 때 쓰는 Outbox도 여기서 구현한다.
 */
public class SessionRegistry implements Outbox {

    /** 밀린 메시지를 보낼 때 대기열이 줄었는지 다시 확인하는 간격. */
    private static final int ROOM_CHECK_MILLIS = 20;

    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    private final Map<String, ClientSession> byUser = new HashMap<>(); // this로 잠금
    private final ServerEventListener events;

    public SessionRegistry(ServerEventListener events) {
        this.events = events;
    }

    /** 로그인 결과: LOGIN_OK 응답과, 같은 아이디로 먼저 접속해 있던(이제 끊어야 할) 연결. */
    public static final class LoginResult {
        private final Packet reply;
        private final ClientSession replaced;

        LoginResult(Packet reply, ClientSession replaced) {
            this.reply = reply;
            this.replaced = replaced;
        }

        public Packet getReply() {
            return reply;
        }

        /** 같은 아이디의 이전 연결. 없으면 null. 부른 쪽이 BYE를 보내고 끊는다(U30). */
        public ClientSession getReplaced() {
            return replaced;
        }
    }

    public void add(ClientSession session) {
        sessions.add(session);
    }

    /** 연결 목록에서 뺀다. 로그인해 있었다면 "지금 그 아이디의 연결이 이 연결일 때만" 아이디 목록에서도 뺀다 (C3). */
    public void remove(ClientSession session) {
        sessions.remove(session);
        String userId = session.getUserId();
        if (userId == null) {
            return;
        }
        List<String> snapshot = null;
        synchronized (this) {
            if (byUser.get(userId) == session) {
                byUser.remove(userId);
                snapshot = List.copyOf(byUser.keySet());
            }
        }
        if (snapshot != null) {
            events.onOnlineUsersChanged(snapshot); // 잠금 밖에서 알린다
        }
    }

    /**
     * 로그인 등록 (C5). 잠금 안에서: 아이디 목록에 등록 → 접속 상태를 보며 응답(LOGIN_OK)을 만든다.
     *
     * @param replyBuilder 접속 여부를 물어볼 수 있는 함수를 받아 LOGIN_OK를 만드는 함수
     */
    public LoginResult login(ClientSession session, String userId, Function<Predicate<String>, Packet> replyBuilder) {
        ClientSession previous;
        Packet reply;
        List<String> snapshot;
        synchronized (this) {
            previous = byUser.put(userId, session);
            session.setUserId(userId);
            reply = replyBuilder.apply(byUser::containsKey);
            snapshot = List.copyOf(byUser.keySet());
        }
        events.onOnlineUsersChanged(snapshot);
        return new LoginResult(reply, previous == session ? null : previous);
    }

    /** 지금 로그인해 있는가. */
    @Override
    public synchronized boolean isOnline(String userId) {
        return byUser.containsKey(userId);
    }

    private synchronized ClientSession sessionOf(String userId) {
        return byUser.get(userId);
    }

    /** 접속 중이면 그 연결의 보내기 대기열에 넣는다 (Outbox). 잠금 밖에서 넣는다. */
    @Override
    public boolean send(String userId, Packet packet) {
        ClientSession s = sessionOf(userId);
        return s != null && s.send(packet);
    }

    /**
     * 대기열이 절반 이상 차 있으면 줄어들 때까지 기다렸다가 넣는다 (Outbox, U36).
     * 기다리는 동안 연결이 끊기거나 같은 아이디의 새 연결로 바뀌면 false (새 연결은 자기 로그인 때 따로 받는다).
     */
    @Override
    public boolean sendWhenRoom(String userId, Packet packet) throws InterruptedException {
        ClientSession s = sessionOf(userId);
        if (s == null) {
            return false;
        }
        while (s.queuedCount() >= s.queueCapacity() / 2) {
            if (s.isClosed() || sessionOf(userId) != s) {
                return false;
            }
            Thread.sleep(ROOM_CHECK_MILLIS);
        }
        return !s.isClosed() && sessionOf(userId) == s && s.send(packet);
    }

    /** 지금 로그인해 있는 아이디들 (복사본). */
    public synchronized List<String> onlineUserIds() {
        return List.copyOf(byUser.keySet());
    }

    /** 지금 연결 수 (로그인 전 포함). */
    public int size() {
        return sessions.size();
    }

    /** 그 아이디로 로그인한 연결이 있으면 이유를 알리고 끊는다 (계정 삭제 등, U30). */
    public void kick(String userId, String reasonForUser, CloseReason closeReason) {
        ClientSession s;
        synchronized (this) {
            s = byUser.get(userId);
        }
        if (s != null) {
            s.kick(reasonForUser, closeReason);
        }
    }

    /** 모든 연결에 이유를 알리고 끊는다 (서버 중지). 끊는 동안 목록이 바뀌므로 복사본을 돈다. */
    public void kickAll(String reasonForUser, CloseReason closeReason) {
        for (ClientSession s : List.copyOf(sessions)) {
            s.kick(reasonForUser, closeReason);
        }
    }
}
