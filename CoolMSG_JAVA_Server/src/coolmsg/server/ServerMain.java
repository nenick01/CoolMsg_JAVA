package coolmsg.server;

import coolmsg.common.ProtocolInfo;

/**
 * 서버 시작점. (아직 뼈대만 있음)
 */
public class ServerMain {

    public static void main(String[] args) {
        System.out.println("CoolMsg 서버 - 프로토콜 버전 " + ProtocolInfo.VERSION
                + ", 포트 " + ProtocolInfo.DEFAULT_PORT);
    }
}
