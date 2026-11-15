package coolmsg.client.net;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import coolmsg.common.ProtocolInfo;

/**
 * 하트비트: 로그인 중 30초마다 PING을 보낸다 (설계서 2.5, REQ-F-115).
 *
 * 서버는 90초 동안 패킷이 없으면 끊고(PC 강제 종료 감지), 클라이언트는 서버의 PONG 덕분에
 * 정상이면 30초마다 무언가를 받는다(서버 침묵 감지, U32).
 */
public class Heartbeat {

    private final ScheduledExecutorService timer;

    public Heartbeat(ServerApi api) {
        this(api, ProtocolInfo.HEARTBEAT_INTERVAL_MILLIS);
    }

    public Heartbeat(ServerApi api, long intervalMillis) {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(api::heartbeat, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        timer.shutdownNow();
    }
}
