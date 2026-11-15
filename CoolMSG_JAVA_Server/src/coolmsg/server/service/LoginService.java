package coolmsg.server.service;

import java.util.function.Predicate;

import coolmsg.common.ProtocolInfo;
import coolmsg.common.file.TimeFormat;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;
import coolmsg.server.config.ServerConfig;
import coolmsg.server.config.UserAccount;
import coolmsg.server.config.UserDirectory;

/**
 * 연결 테스트·로그인 업무 규칙 (설계서 3.1, 2.3, 2.4).
 *
 * 소켓을 모른다. 요청 패킷을 받아 판단하거나 응답 패킷을 만들 뿐이라, 서버를 띄우지 않고도 테스트할 수 있다.
 * 연결(세션)에 로그인 상태를 기록하는 일은 통신 쪽(RequestDispatcher, SessionRegistry)이 한다.
 */
public class LoginService {

    /** 아이디가 없거나, 삭제됐거나, 비밀번호가 틀린 경우 모두 같은 문구 (설계서 2.3, A1). */
    public static final String WRONG_CREDENTIALS = "아이디 또는 비밀번호가 틀렸습니다.";
    public static final String ALREADY_LOGGED_IN = "이미 로그인되어 있습니다.";

    private final ServerConfig config;
    private final UserDirectory users;

    public LoginService(ServerConfig config, UserDirectory users) {
        this.config = config;
        this.users = users;
    }

    /** PING → PONG (서버 이름, 프로토콜 버전, 서버 시각). 로그인 전에도 허용된다. */
    public Packet ping(Packet request) {
        return Packet.replyTo(request, PacketType.PONG)
                .put(HeaderKeys.SERVER_NAME, config.getServerName())
                .put(HeaderKeys.VERSION, ProtocolInfo.VERSION)
                .put(HeaderKeys.SERVER_TIME, TimeFormat.format(TimeFormat.now()));
    }

    /** 로그인 확인 결과. 성공이면 userId, 실패면 이유(사용자에게 보여 줄 문장). */
    public static final class LoginCheck {
        private final String userId;
        private final String failReason;

        private LoginCheck(String userId, String failReason) {
            this.userId = userId;
            this.failReason = failReason;
        }

        public boolean isOk() {
            return failReason == null;
        }

        public String getUserId() {
            return userId;
        }

        public String getFailReason() {
            return failReason;
        }
    }

    /**
     * LOGIN 요청 확인: 버전 → 아이디·비밀번호 (설계서 2.7).
     * 아이디가 없거나 삭제됐거나 비밀번호가 틀리면 모두 같은 문구로 실패한다.
     */
    public LoginCheck check(Packet request) {
        String version = request.get(HeaderKeys.VERSION);
        if (!String.valueOf(ProtocolInfo.VERSION).equals(version)) {
            return new LoginCheck(null, "프로그램 버전이 서버와 다릅니다 (서버 " + ProtocolInfo.VERSION
                    + ", 프로그램 " + version + "). 프로그램을 업데이트하세요.");
        }
        String userId = request.get(HeaderKeys.USER_ID);
        String password = request.get(HeaderKeys.PASSWORD);
        if (userId == null || password == null) {
            return new LoginCheck(null, WRONG_CREDENTIALS);
        }
        if (!users.authenticate(userId, password.toCharArray())) {
            return new LoginCheck(null, WRONG_CREDENTIALS);
        }
        return new LoginCheck(userId, null);
    }

    /**
     * LOGIN_OK: 데이터는 사용자 목록 "아이디|이름|online" (등록 순서, 삭제된 사용자 제외, 설계서 2.3).
     *
     * @param isOnline 지금 접속해 있는지 알려 주는 함수 (SessionRegistry가 잠금 안에서 넘긴다)
     */
    public Packet loginOk(Packet request, Predicate<String> isOnline) {
        StringBuilder list = new StringBuilder();
        for (UserAccount u : users.getUsers()) {
            if (u.isDeleted()) {
                continue;
            }
            list.append(u.getId()).append('|').append(u.getName()).append('|')
                    .append(isOnline.test(u.getId())).append('\n');
        }
        return Packet.replyTo(request, PacketType.LOGIN_OK).setText(list.toString());
    }

    public static Packet loginFail(Packet request, String reason) {
        return Packet.replyTo(request, PacketType.LOGIN_FAIL).put(HeaderKeys.REASON, reason);
    }
}
