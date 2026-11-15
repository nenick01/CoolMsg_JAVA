package coolmsg.client.net;

import coolmsg.common.ProtocolInfo;

/**
 * 로그인 화면 '서버' 칸의 입력을 해석한 결과 (설계서 4.3, 5장 U26).
 *
 * - "192.168.0.10"      → 192.168.0.10, 포트 9000
 * - "192.168.0.10:9100" → 192.168.0.10, 포트 9100
 * - "localhost"         → localhost, 포트 9000
 * IPv6 주소(콜론이 여러 개)는 지원하지 않는다.
 */
public final class ServerAddress {

    private final String host;
    private final int port;

    public ServerAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * 입력을 해석한다.
     *
     * @throws IllegalArgumentException 비어 있음, 포트가 숫자가 아니거나 범위(1~65535) 밖, IPv6 형식.
     *                                  메시지는 그대로 화면에 보여 줄 수 있는 문장이다
     */
    public static ServerAddress parse(String text) {
        String input = (text == null) ? "" : text.strip();
        if (input.isEmpty()) {
            throw new IllegalArgumentException("서버 주소를 입력하세요.");
        }
        int colon = input.indexOf(':');
        if (colon < 0) {
            return new ServerAddress(input, ProtocolInfo.DEFAULT_PORT);
        }
        if (input.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("IPv6 주소는 지원하지 않습니다. 'IP' 또는 'IP:포트' 형식으로 입력하세요.");
        }
        String host = input.substring(0, colon).strip();
        String portText = input.substring(colon + 1).strip();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("':' 앞에 서버 IP를 입력하세요.");
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("포트는 숫자여야 합니다: " + portText);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("포트는 1~65535 사이여야 합니다: " + port);
        }
        return new ServerAddress(host, port);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
