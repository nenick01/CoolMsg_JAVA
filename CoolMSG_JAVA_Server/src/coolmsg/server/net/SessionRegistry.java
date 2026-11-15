package coolmsg.server.net;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지금 연결된 세션 목록 (설계서 3.1).
 *
 * 구현 2단계: 로그인 전 연결을 포함한 모든 연결의 목록만 있다(접속 수 제한, 서버 중지 때 전체 닫기).
 * 구현 3단계에서 "아이디 → 지금 연결" 목록과 PRESENCE 알림을 더한다.
 *
 * 받기 스레드·접속 받기 스레드·Swing 스레드가 동시에 쓰므로 스레드 안전한 집합을 쓴다.
 */
public class SessionRegistry {

    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();

    public void add(ClientSession session) {
        sessions.add(session);
    }

    public void remove(ClientSession session) {
        sessions.remove(session);
    }

    /** 지금 연결 수 (로그인 전 포함). */
    public int size() {
        return sessions.size();
    }

    /** 모든 연결을 닫는다. 닫는 동안 목록이 바뀌므로 복사본을 돌며 닫는다. */
    public void closeAll(CloseReason reason) {
        for (ClientSession s : List.copyOf(sessions)) {
            s.close(reason);
        }
    }
}
