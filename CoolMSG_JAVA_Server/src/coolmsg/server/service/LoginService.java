package coolmsg.server.service;

import coolmsg.common.ProtocolInfo;
import coolmsg.common.file.TimeFormat;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;
import coolmsg.server.config.ServerConfig;

/**
 * 연결 테스트·로그인 업무 규칙 (설계서 3.1, 2.4).
 *
 * 소켓을 모른다. 요청 패킷을 받아 응답 패킷을 돌려줄 뿐이라, 서버를 띄우지 않고도 테스트할 수 있다.
 * 구현 2단계: PING만. LOGIN은 3단계.
 */
public class LoginService {

    private final ServerConfig config;

    public LoginService(ServerConfig config) {
        this.config = config;
    }

    /** PING → PONG (서버 이름, 프로토콜 버전, 서버 시각). 로그인 전에도 허용된다. */
    public Packet ping(Packet request) {
        return Packet.replyTo(request, PacketType.PONG)
                .put(HeaderKeys.SERVER_NAME, config.getServerName())
                .put(HeaderKeys.VERSION, ProtocolInfo.VERSION)
                .put(HeaderKeys.SERVER_TIME, TimeFormat.format(TimeFormat.now()));
    }
}
