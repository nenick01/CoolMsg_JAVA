package coolmsg.server.net;

import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;
import java.util.function.Predicate;

import coolmsg.server.ServerEventListener;
import coolmsg.server.config.OrgChart;
import coolmsg.server.service.LoginService;
import coolmsg.server.service.MessageService;

/**
 * 받은 패킷의 종류(type)를 보고 알맞은 서비스로 넘긴다 (설계서 3.1).
 *
 * 소켓 코드(ClientSession)와 업무 규칙(service)을 나누는 경계다.
 * - 모르는 종류, 서버가 보내는 종류(PONG 등)를 받으면: ERROR "알 수 없는 명령" (연결은 유지, 설계서 3.7)
 * - 로그인 전에 PING·LOGIN 외의 요청: ERROR "로그인이 필요합니다" (설계서 2.3)
 * - 첨부파일(UPLOAD·DOWNLOAD)과 아직 만들지 않은 명령: ERROR "지원하지 않는 기능"
 *
 * 구현 6단계까지: PING, LOGIN(+ 밀린 메시지·알림), LOGOUT, SEND, ACK, READ, STATUS, ORG.
 *
 * 응답 순서가 중요한 요청(LOGIN, SEND)은 여기서 응답을 직접 대기열에 넣고 null을 돌려준다.
 *   LOGIN: LOGIN_OK → 밀린 MSG (3.8.5 ②)
 *   SEND : SEND_OK → 받는 사람에게 MSG (3.8.5 ①)
 */
public class RequestDispatcher {

    public static final String DUPLICATE_LOGIN_MESSAGE = "다른 곳에서 같은 아이디로 로그인하여 연결이 끊겼습니다.";

    private final LoginService loginService;
    private final MessageService messageService;
    private final SessionRegistry registry;
    private final ServerEventListener events;
    private OrgChart orgChart; // 없으면 ORG에 ORG_NONE
    private Predicate<String> activeUser = id -> true;

    public RequestDispatcher(LoginService loginService, MessageService messageService, SessionRegistry registry,
            ServerEventListener events) {
        this.loginService = loginService;
        this.messageService = messageService;
        this.registry = registry;
        this.events = events;
    }

    /** 조직도를 연결한다 (ServerContext가 시작할 때 한 번). activeUser: 삭제되지 않은 사용자인가 (U55). */
    public void setOrgChart(OrgChart orgChart, Predicate<String> activeUser) {
        this.orgChart = orgChart;
        this.activeUser = activeUser;
    }

    /** 요청을 처리하고 응답을 돌려준다. 응답이 없는 요청(LOGOUT, ACK 등)은 null. */
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
            case LOGIN:
                return login(session, request);
            case SEND:
                messageService.send(session.getUserId(), request, session::send);
                return null;
            case ACK:
                messageService.ack(session.getUserId(), request);
                return null;
            case READ:
                messageService.read(session.getUserId(), request);
                return null;
            case STATUS:
                return messageService.status(session.getUserId(), request);
            case ORG:
                return org(request);
            case LOGOUT:
                session.close(CloseReason.LOGOUT);
                return null;
            default:
                return error(request, "지원하지 않는 기능입니다: " + type);
        }
    }

    private Packet login(ClientSession session, Packet request) {
        if (session.isLoggedIn()) {
            return LoginService.loginFail(request, LoginService.ALREADY_LOGGED_IN); // U31
        }
        LoginService.LoginCheck check = loginService.check(request);
        String attempted = request.get(HeaderKeys.USER_ID);
        if (!check.isOk()) {
            events.onLog("로그인 실패 #" + session.getId() + " (" + attempted + "): " + check.getFailReason());
            return LoginService.loginFail(request, check.getFailReason());
        }
        SessionRegistry.LoginResult result = registry.login(session, check.getUserId(),
                isOnline -> loginService.loginOk(request, isOnline));
        events.onLog("로그인 #" + session.getId() + ": " + check.getUserId());
        if (result.getReplaced() != null) {
            result.getReplaced().kick(DUPLICATE_LOGIN_MESSAGE, CloseReason.DUPLICATE_LOGIN); // U30
        }
        session.send(result.getReply());                        // LOGIN_OK 먼저
        messageService.deliverPendingAsync(check.getUserId());  // 그다음 밀린 MSG (별도 스레드, U36)
        return null;
    }

    /** ORG → ORG_OK(org.txt 형식, 활성 사용자만) / ORG_NONE (설계서 2.10). */
    private Packet org(Packet request) {
        String text = orgChart == null ? null : orgChart.packetText(activeUser);
        if (text == null) {
            return Packet.replyTo(request, PacketType.ORG_NONE).put(HeaderKeys.REASON, OrgChart.NONE_REASON);
        }
        return Packet.replyTo(request, PacketType.ORG_OK).setText(text);
    }

    /** 요청의 reqId를 붙인 ERROR 응답. */
    public static Packet error(Packet request, String reason) {
        return Packet.replyTo(request, PacketType.ERROR).put(HeaderKeys.REASON, reason);
    }
}
