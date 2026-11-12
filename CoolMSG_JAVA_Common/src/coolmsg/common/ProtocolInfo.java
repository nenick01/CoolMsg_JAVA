package coolmsg.common;

/**
 * 서버와 클라이언트가 함께 쓰는 프로토콜 상수.
 * 값의 근거는 docs/03_설계서.md 2장(통신 프로토콜) 참고.
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

    private ProtocolInfo() {
    }
}
