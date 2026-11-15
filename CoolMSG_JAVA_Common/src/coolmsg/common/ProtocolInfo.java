package coolmsg.common;

/**
 * 서버와 클라이언트가 함께 쓰는 프로토콜 상수.
 * 값의 근거는 docs/03_설계서.md 2장(통신 프로토콜)과 5장(구현 전 세부 결정) 참고.
 * 서버에서 바꿀 수 있는 값(포트, 보관 기간 등)은 config/server.txt가 우선하고, 여기 값은 기본값이다.
 */
public final class ProtocolInfo {

    /** 프로토콜 버전. PONG, LOGIN에서 서로 확인한다. */
    public static final int VERSION = 1;

    /** 서버 기본 포트. */
    public static final int DEFAULT_PORT = 9000;

    /** 패킷 헤더 최대 크기 (64KB). */
    public static final int MAX_HEADER_BYTES = 64 * 1024;

    /** 패킷 데이터 최대 크기 (11MB = 첨부 10MB + 여유). */
    public static final int MAX_DATA_BYTES = 11 * 1024 * 1024;

    /** 첨부파일 최대 크기 (10MB). */
    public static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    /** 메시지 본문 최대 크기 (1MB, UTF-8 기준). 설계서 5장 U5. */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    /** 한 메시지의 받는 사람 + 참조 최대 인원. 설계서 5장 U5. */
    public static final int MAX_RECIPIENTS = 100;

    /** 클라이언트 요청의 응답 대기 시간 (10초). 설계서 5장 U10. */
    public static final int REQUEST_TIMEOUT_MILLIS = 10_000;

    /** 하트비트(PING) 간격 (30초). */
    public static final int HEARTBEAT_INTERVAL_MILLIS = 30_000;

    /** 이 시간 동안 패킷이 없으면 연결이 끊긴 것으로 본다 (90초). */
    public static final int IDLE_TIMEOUT_MILLIS = 90_000;

    /** 연결 테스트의 연결·응답 제한 시간 (각 3초). */
    public static final int CONNECT_TEST_TIMEOUT_MILLIS = 3_000;

    /** 조직도 응답 대기 시간 (3초). 넘으면 등록 순서로 임시 표시. */
    public static final int ORG_RESPONSE_TIMEOUT_MILLIS = 3_000;

    private ProtocolInfo() {
    }
}
