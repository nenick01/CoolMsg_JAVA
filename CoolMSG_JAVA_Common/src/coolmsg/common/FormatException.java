package coolmsg.common;

import java.io.IOException;

/**
 * 패킷·파일·시각 문자열의 형식이 규칙(설계서 2.1, 5장 U1·U2)에 맞지 않을 때 던지는 예외.
 *
 * IOException의 하위 클래스라서, 부르는 쪽은 "파일을 못 읽었다", "통신이 끊겼다"와
 * 같은 방식으로 처리할 수 있다.
 *
 * 주의: 패킷을 읽다가 이 예외가 나면 스트림 위치가 어긋났을 수 있으므로
 * 그 연결은 더 쓰지 말고 닫아야 한다.
 */
public class FormatException extends IOException {

    private static final long serialVersionUID = 1L;

    public FormatException(String message) {
        super(message);
    }

    public FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
