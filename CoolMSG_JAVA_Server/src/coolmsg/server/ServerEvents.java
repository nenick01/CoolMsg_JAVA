package coolmsg.server;

/**
 * 서버 사건을 지금 연결된 화면(또는 테스트)에 전달하는 중계기.
 *
 * 서버 객체들은 처음 만들 때 이 중계기를 받아 두고, 화면은 나중에 setTarget으로 연결한다.
 * 화면이 아직 없으면 사건은 그냥 버린다. target은 여러 스레드가 읽으므로 volatile로 둔다.
 */
public class ServerEvents implements ServerEventListener {

    private volatile ServerEventListener target;

    public void setTarget(ServerEventListener target) {
        this.target = target;
    }

    @Override
    public void onLog(String message) {
        ServerEventListener t = target;
        if (t != null) {
            t.onLog(message);
        }
    }

    @Override
    public void onServerStateChanged(boolean running) {
        ServerEventListener t = target;
        if (t != null) {
            t.onServerStateChanged(running);
        }
    }
}
