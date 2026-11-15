package coolmsg.server;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 서버 사건을 지금 연결된 화면들(또는 테스트)에 전달하는 중계기.
 *
 * 서버 객체들은 처음 만들 때 이 중계기를 받아 두고, 화면은 나중에 addTarget으로 연결한다.
 * 화면이 여럿일 수 있다([서버 상태] 탭, [사용자 관리] 탭). 여러 스레드가 동시에 읽으므로
 * 읽기가 많고 쓰기가 드문 경우에 맞는 CopyOnWriteArrayList를 쓴다.
 */
public class ServerEvents implements ServerEventListener {

    private final List<ServerEventListener> targets = new CopyOnWriteArrayList<>();

    public void addTarget(ServerEventListener target) {
        targets.add(target);
    }

    @Override
    public void onLog(String message) {
        for (ServerEventListener t : targets) {
            t.onLog(message);
        }
    }

    @Override
    public void onServerStateChanged(boolean running) {
        for (ServerEventListener t : targets) {
            t.onServerStateChanged(running);
        }
    }

    @Override
    public void onOnlineUsersChanged(List<String> onlineUserIds) {
        for (ServerEventListener t : targets) {
            t.onOnlineUsersChanged(onlineUserIds);
        }
    }
}
