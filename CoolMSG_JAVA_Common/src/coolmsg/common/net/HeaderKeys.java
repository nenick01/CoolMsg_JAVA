package coolmsg.common.net;

/**
 * 패킷 헤더 키 이름 (설계서 2.3 명령 목록).
 *
 * 서버와 클라이언트가 같은 키를 각자 문자열로 쓰면 오타 하나로 서로 못 알아듣는다.
 * 그래서 키 이름을 Common에 한 번만 정의하고 양쪽이 이 상수를 쓴다.
 * (메시지 관련 키는 MessageData.KEY_* 에 있다.)
 */
public final class HeaderKeys {

    public static final String TYPE = "type";
    public static final String REQ_ID = "reqId";
    public static final String REASON = "reason";

    /** PONG, LOGIN */
    public static final String VERSION = "version";
    /** PONG */
    public static final String SERVER_NAME = "serverName";
    /** PONG */
    public static final String SERVER_TIME = "serverTime";

    /** LOGIN, PRESENCE, RECEIPT */
    public static final String USER_ID = "userId";
    /** LOGIN (로그에서는 Packet.toString이 값을 가린다) */
    public static final String PASSWORD = "password";
    /** 메시지 ID (SEND_OK, ACK, READ, STATUS 등). MessageData.KEY_MESSAGE_ID와 같은 값. */
    public static final String MESSAGE_ID = "messageId";
    /** 서버가 정한 보낸 시각 (SEND_OK). MessageData.KEY_SENT_AT과 같은 값. */
    public static final String SENT_AT = "sentAt";

    private HeaderKeys() {
    }
}
