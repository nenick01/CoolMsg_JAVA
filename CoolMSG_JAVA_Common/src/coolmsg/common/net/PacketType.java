package coolmsg.common.net;

/**
 * 패킷 종류 (설계서 2.3 명령 목록).
 * 문자열 "SEND" 대신 PacketType.SEND를 쓰면 "SNED" 같은 오타를 컴파일 단계에서 잡을 수 있다.
 */
public enum PacketType {

    // ----- 클라이언트 → 서버 (요청) -----
    PING(true),
    LOGIN(true),
    SEND(false),
    ACK(false),
    READ(false),
    STATUS(false),
    ORG(false),
    LOGOUT(false),
    /** 첨부파일: 제출 범위에서는 형식만 있다 (설계서 1.3). */
    UPLOAD(false),
    /** 첨부파일: 제출 범위에서는 형식만 있다 (설계서 1.3). */
    DOWNLOAD(false),

    // ----- 서버 → 클라이언트 (응답) -----
    PONG,
    LOGIN_OK,
    LOGIN_FAIL,
    SEND_OK,
    SEND_FAIL,
    STATUS_OK,
    STATUS_FAIL,
    ORG_OK,
    ORG_NONE,
    UPLOAD_OK,
    UPLOAD_FAIL,
    FILE,
    FILE_EXPIRED,
    FILE_DENIED,
    ERROR,

    // ----- 서버 → 클라이언트 (서버가 먼저 보냄) -----
    MSG,
    PRESENCE,
    RECEIPT;

    private final boolean request;
    private final boolean allowedBeforeLogin;

    /** 서버가 보내는 패킷. */
    PacketType() {
        this.request = false;
        this.allowedBeforeLogin = false;
    }

    /** 클라이언트가 보내는 요청 패킷. */
    PacketType(boolean allowedBeforeLogin) {
        this.request = true;
        this.allowedBeforeLogin = allowedBeforeLogin;
    }

    /** 클라이언트가 서버에 보내는 요청인가. */
    public boolean isRequest() {
        return request;
    }

    /** 로그인 전에도 보낼 수 있는 요청인가 (PING, LOGIN). */
    public boolean isAllowedBeforeLogin() {
        return allowedBeforeLogin;
    }

    /** 이름으로 찾는다. 모르는 이름이면 예외 대신 null (서버가 ERROR로 응답할 수 있게). */
    public static PacketType fromName(String name) {
        for (PacketType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
