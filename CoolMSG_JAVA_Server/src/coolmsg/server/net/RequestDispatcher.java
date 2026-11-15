package coolmsg.server.net;

import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;
import coolmsg.server.service.LoginService;

/**
 * 받은 패킷의 종류(type)를 보고 알맞은 서비스로 넘긴다 (설계서 3.1).
 *
 * 소켓 코드(ClientSession)와 업무 규칙(service)을 나누는 경계다.
 * - 모르는 종류, 서버가 보내는 종류(PONG 등)를 받으면: ERROR "알 수 없는 명령" (연결은 유지, 설계서 3.7)
 * - 로그인 전에 PING·LOGIN 외의 요청: ERROR "로그인이 필요합니다" (설계서 2.3)
 * - 첨부파일(UPLOAD·DOWNLOAD)과 아직 만들지 않은 명령: ERROR "지원하지 않는 기능"
 *
 * 구현 2단계: PING만 처리한다. 다음 단계에서 case를 하나씩 더한다.
 */
public class RequestDispatcher {

    private final LoginService loginService;

    public RequestDispatcher(LoginService loginService) {
        this.loginService = loginService;
    }

    /** 요청을 처리하고 응답을 돌려준다. 응답이 없는 요청(ACK 등)은 null. */
    public Packet dispatch(ClientSession session, Packet request) {
        PacketType type = request.getType();
        if (type == null || !type.isRequest()) {
            return error(request, "알 수 없는 명령입니다: " + request.getTypeName());
        }
        if (!session.isLoggedIn() && !type.isAllowedBeforeLogin()) {
            return error(request, "로그인이 필요합니다.");
        }
        switch (type) {
            case PING:
                return loginService.ping(request);
            default:
                return error(request, "지원하지 않는 기능입니다: " + type);
        }
    }

    /** 요청의 reqId를 붙인 ERROR 응답. */
    public static Packet error(Packet request, String reason) {
        return Packet.replyTo(request, PacketType.ERROR).put(HeaderKeys.REASON, reason);
    }
}
