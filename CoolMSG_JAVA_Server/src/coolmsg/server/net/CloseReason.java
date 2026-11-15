package coolmsg.server.net;

/**
 * 연결이 닫힌 이유. 관리 화면 로그에 남긴다 (설계서 5장 U29).
 */
public enum CloseReason {

    REMOTE_CLOSED("상대가 연결을 닫음"),
    IDLE_TIMEOUT("일정 시간 동안 패킷이 없음"),
    FORMAT_ERROR("패킷 형식 오류"),
    QUEUE_FULL("보내기 대기열이 가득 참 (클라이언트가 너무 느림)"),
    IO_ERROR("통신 오류"),
    SERVER_STOP("서버 중지");

    private final String description;

    CloseReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
