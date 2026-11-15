package coolmsg.client;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import coolmsg.client.net.ConnectionTester;
import coolmsg.client.net.ConnectionTester.Outcome;
import coolmsg.client.net.ConnectionTester.Result;
import coolmsg.client.net.ServerAddress;
import coolmsg.common.ProtocolInfo;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketReader;
import coolmsg.common.net.PacketType;
import coolmsg.common.net.PacketWriter;

/**
 * 클라이언트 테스트 (구현 2단계: 서버 주소 해석, 연결 테스트).
 *
 * 연결 테스트는 진짜 서버 대신 이 테스트 안에서 만든 "가짜 서버"(ServerSocket + Common의 PacketReader/Writer)에
 * 접속해 확인한다. 클라이언트 프로젝트가 서버 프로젝트를 몰라도 되게 하기 위해서다.
 * 실행: VS Code "Test: Client"
 */
public class ClientTest {

    private static int passed = 0;
    private static int failed = 0;

    private interface Action {
        void run() throws Exception;
    }

    /** 가짜 서버가 PING을 받았을 때 할 일. */
    private interface FakeBehavior {
        void handle(Packet ping, PacketWriter out) throws IOException;
    }

    public static void main(String[] args) throws Exception {
        serverAddress();
        connectionTester();

        System.out.println();
        System.out.println("결과: 통과 " + passed + " / 실패 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ===================================================================
    // ServerAddress (U26)
    // ===================================================================
    private static void serverAddress() {
        section("ServerAddress - 'IP' 또는 'IP:포트' 해석 (U26)");

        ServerAddress a = ServerAddress.parse("192.168.0.10");
        check("IP만 → 포트 9000", a.getHost().equals("192.168.0.10") && a.getPort() == ProtocolInfo.DEFAULT_PORT);
        ServerAddress b = ServerAddress.parse("  192.168.0.10:9100  ");
        check("IP:포트, 앞뒤 공백 무시", b.getHost().equals("192.168.0.10") && b.getPort() == 9100);
        check("localhost", ServerAddress.parse("localhost").getHost().equals("localhost"));
        check("경계값 포트 1·65535", ServerAddress.parse("h:1").getPort() == 1 && ServerAddress.parse("h:65535").getPort() == 65535);

        expectThrows("빈 칸 → 안내", IllegalArgumentException.class, () -> ServerAddress.parse("   "));
        expectThrows("포트가 숫자 아님", IllegalArgumentException.class, () -> ServerAddress.parse("host:abc"));
        expectThrows("포트 0", IllegalArgumentException.class, () -> ServerAddress.parse("host:0"));
        expectThrows("포트 65536", IllegalArgumentException.class, () -> ServerAddress.parse("host:65536"));
        expectThrows("':' 앞이 비어 있음", IllegalArgumentException.class, () -> ServerAddress.parse(":9000"));
        expectThrows("IPv6 형식(콜론 여러 개) → 미지원 안내", IllegalArgumentException.class, () -> ServerAddress.parse("::1"));
    }

    // ===================================================================
    // ConnectionTester (설계서 2.4)
    // ===================================================================
    private static void connectionTester() throws Exception {
        section("ConnectionTester - 연결 테스트 결과 분류 (설계서 2.4)");
        ConnectionTester tester = new ConnectionTester(1000); // 테스트에서는 1초 제한

        Result ok = withFakeServer((ping, out) -> out.write(Packet.replyTo(ping, PacketType.PONG)
                .put(HeaderKeys.SERVER_NAME, "가짜 서버").put(HeaderKeys.VERSION, ProtocolInfo.VERSION)), tester);
        check("PONG → 성공, 서버 이름·버전·응답 시간",
                ok.isSuccess() && "가짜 서버".equals(ok.getServerName()) && ok.getVersion() == ProtocolInfo.VERSION
                        && ok.getMillis() >= 0);

        Result version = withFakeServer((ping, out) -> out.write(Packet.replyTo(ping, PacketType.PONG)
                .put(HeaderKeys.SERVER_NAME, "옛 서버").put(HeaderKeys.VERSION, 99)), tester);
        check("다른 버전 → 버전 다름 (서버 버전 알려 줌)",
                version.getOutcome() == Outcome.VERSION_MISMATCH && version.getVersion() == 99);

        Result full = withFakeServer((ping, out) -> out.write(new Packet(PacketType.ERROR)
                .put(HeaderKeys.REASON, "접속자가 너무 많습니다. 관리자에게 문의해주세요")), tester);
        check("서버가 ERROR로 거절 → 이유를 그대로 보여 줌",
                full.getOutcome() == Outcome.SERVER_ERROR && full.getDetail().contains("접속자가 너무 많습니다"));

        Result silent = withFakeServer((ping, out) -> {
            try {
                Thread.sleep(2000); // 응답하지 않음
            } catch (InterruptedException ignored) {
                // 무시
            }
        }, tester);
        check("응답이 1초 안에 없음 → 시간 초과", silent.getOutcome() == Outcome.TIMEOUT);

        Result closed = withFakeServer((ping, out) -> { /* 응답 없이 바로 닫음 */ }, tester);
        check("응답 없이 연결을 닫음 → CoolMsg 서버가 아닌 것 같음", closed.getOutcome() == Outcome.PROTOCOL_ERROR);

        Result wrong = withFakeServer((ping, out) -> out.write(new Packet(PacketType.MSG).put("messageId", "x")), tester);
        check("엉뚱한 응답(MSG) → CoolMsg 서버가 아닌 것 같음", wrong.getOutcome() == Outcome.PROTOCOL_ERROR);

        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        } // 닫았으므로 아무도 듣지 않는 포트
        Result refused = tester.test(new ServerAddress("localhost", freePort));
        check("서버가 꺼진 포트 → 연결 거부", refused.getOutcome() == Outcome.REFUSED);

        Result unknown = tester.test(new ServerAddress("no-such-host.invalid", 9000));
        check("없는 주소(.invalid) → 주소를 찾을 수 없음", unknown.getOutcome() == Outcome.UNKNOWN_HOST);
    }

    /** 가짜 서버를 띄우고, 그 서버에 연결 테스트를 한 결과를 돌려준다. */
    private static Result withFakeServer(FakeBehavior behavior, ConnectionTester tester) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    Packet ping = new PacketReader(s.getInputStream()).read();
                    behavior.handle(ping, new PacketWriter(s.getOutputStream()));
                } catch (IOException ignored) {
                    // 테스트 쪽이 먼저 닫은 경우
                }
            });
            t.setDaemon(true);
            t.start();
            Result r = tester.test(new ServerAddress("localhost", server.getLocalPort()));
            t.join(3000);
            return r;
        }
    }

    // ===================================================================
    private static void section(String name) {
        System.out.println();
        System.out.println("[" + name + "]");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    private static void expectThrows(String name, Class<? extends Throwable> expected, Action action) {
        try {
            action.run();
            check(name + "  (예외가 나지 않음)", false);
        } catch (Throwable t) {
            boolean ok = expected.isInstance(t);
            check(ok ? name : name + "  (다른 예외: " + t + ")", ok);
        }
    }
}
