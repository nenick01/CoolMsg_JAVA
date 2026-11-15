package coolmsg.server;

import java.util.List;

/**
 * 서버 내부(통신·서비스)가 관리 화면에 알리는 약속 (설계서 3.1).
 *
 * 통신 코드(net)는 이 인터페이스만 알고 Swing 화면은 모른다. 그래서 화면 없이도(테스트 등) 서버가 동작한다.
 * 이 메서드들은 소켓 스레드에서 불린다. 구현하는 화면은 SwingUtilities.invokeLater로 화면을 고쳐야 한다(C9).
 *
 * net이 쓰는 인터페이스라 ui 패키지가 아니라 여기(coolmsg.server)에 둔다 (의존 방향: ui → net, 반대는 없음).
 */
public interface ServerEventListener {

    /** 관리 화면 로그에 남길 한 줄. */
    void onLog(String message);

    /** 접속 받기가 시작·중지됨. */
    void onServerStateChanged(boolean running);

    /**
     * 로그인한 사용자 목록이 바뀜 (구현 3단계).
     * 기본 구현은 아무것도 하지 않는다. 관심 있는 화면만 다시 정의한다.
     *
     * @param onlineUserIds 지금 로그인해 있는 아이디들 (복사본)
     */
    default void onOnlineUsersChanged(List<String> onlineUserIds) {
    }
}
