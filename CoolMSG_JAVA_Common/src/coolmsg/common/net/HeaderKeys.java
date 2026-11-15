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
    /** 상태 (RECEIPT: DELIVERED/READ). */
    public static final String STATE = "state";
    /** 상태가 바뀐 시각 (RECEIPT). */
    public static final String AT = "at";
    /** 대기 파일에서 꺼낸 지난 알림이면 true (RECEIPT, U51 — 클라이언트는 알림 창을 띄우지 않는다). */
    public static final String QUEUED = "queued";
    /** 접속 여부 (PRESENCE: true/false). */
    public static final String ONLINE = "online";
    /** STATUS_OK의 받는 사람별 상태 "state.{아이디}" 앞부분. 삭제된 사용자는 DELETED (U4). */
    public static final String STATE_PREFIX = "state.";
    /** STATUS_OK의 받는 사람별 읽은 시각 "readAt.{아이디}" 앞부분. */
    public static final String READ_AT_PREFIX = "readAt.";

    private HeaderKeys() {
    }
}
