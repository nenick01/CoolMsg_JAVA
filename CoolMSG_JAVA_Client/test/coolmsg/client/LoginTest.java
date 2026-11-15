package coolmsg.client;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import coolmsg.client.net.Connection;
import coolmsg.client.net.Heartbeat;
import coolmsg.client.net.ServerAddress;
import coolmsg.client.net.ServerApi;
import coolmsg.client.store.ClientPaths;
import coolmsg.client.store.ClientSettings;
import coolmsg.common.ProtocolInfo;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketReader;
import coolmsg.common.net.PacketType;
import coolmsg.common.net.PacketWriter;

/**
 * 구현 3단계 클라이언트 테스트: 저장 위치·로그인 정보, 연결(대기표·끊김 감지), 로그인 요청, 하트비트.
 * 진짜 서버 대신 각본대로 움직이는 가짜 서버에 접속해 확인한다. ClientTest.main에서 불린다.
 */
final class LoginTest {

    private static BiConsumer<String, Boolean> check;

    /** 가짜 서버의 각본: 접속한 클라이언트와 주고받을 내용. */
    private interface Script {
        void play(PacketReader in, PacketWriter out) throws Exception;
    }

    /** 연결 끊김 알림을 모아 두는 Listener. */
    private static final class Recorder implements Connection.Listener {
        final List<Packet> packets = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch disconnected = new CountDownLatch(1);
        volatile String reason;
        volatile boolean byServer;
        volatile int disconnectCount;

        @Override
        public void onPacket(Packet packet) {
            packets.add(packet);
        }

        @Override
        public void onDisconnected(String r, boolean bs) {
            reason = r;
            byServer = bs;
            disconnectCount++;
            disconnected.countDown();
        }

        boolean awaitDisconnect() throws InterruptedException {
            return disconnected.await(3, TimeUnit.SECONDS);
        }
    }

    private LoginTest() {
    }

    static void run(BiConsumer<String, Boolean> checker, Consumer<String> section) throws Exception {
        check = checker;
        section.accept("ClientPaths·ClientSettings - 사용자 홈 폴더 저장 (U34)");
        settings();
        section.accept("ServerApi - 로그인 요청과 응답 (대기표, reqId)");
        loginRequest();
        section.accept("Connection - 끊김 감지 (BYE, 서버 종료, 서버 침묵, 응답 시간 초과)");
        disconnects();
        section.accept("Connection - 로그아웃·하트비트·서버가 먼저 보낸 패킷");
        logoutHeartbeatPush();
    }

    // ===================================================================
    private static void settings() throws Exception {
        ClientPaths home = ClientPaths.inUserHome();
        check("실제 저장 위치는 사용자 홈 폴더의 .coolmsg",
                home.base().equals(Path.of(System.getProperty("user.home"), ".coolmsg"))
                        && home.settingsFile().getFileName().toString().equals("client.txt")
                        && home.mailboxDir("kim").equals(home.base().resolve("kim")));

        Path base = Files.createTempDirectory("coolmsg-client").resolve(".coolmsg");
        ClientPaths paths = new ClientPaths(base);
        ClientSettings missing = ClientSettings.load(paths.settingsFile());
        check("파일 없음 → 서버 localhost, 아이디 빈칸",
                missing.getServer().equals("localhost") && missing.getUserId().isEmpty());

        ClientSettings s = new ClientSettings();
        s.setServer("192.168.0.10:9100");
        s.setUserId("kim");
        s.save(paths.settingsFile());
        ClientSettings back = ClientSettings.load(paths.settingsFile());
        check("저장 후 다시 읽기 (폴더 자동 생성)",
                back.getServer().equals("192.168.0.10:9100") && back.getUserId().equals("kim"));
        check("비밀번호는 파일에 없음",
                !new String(Files.readAllBytes(paths.settingsFile()), StandardCharsets.UTF_8).toLowerCase().contains("password"));

        Files.writeString(paths.settingsFile(), "# 메모\r\n이상한 줄\r\nserver = 10.0.0.5 \r\nuserId=lee\r\n");
        ClientSettings crlf = ClientSettings.load(paths.settingsFile());
        check("\\r\\n·공백·이상한 줄이 있어도 관대하게 읽음",
                crlf.getServer().equals("10.0.0.5") && crlf.getUserId().equals("lee"));
    }

    // ===================================================================
    private static void loginRequest() throws Exception {
        List<Packet> seen = Collections.synchronizedList(new ArrayList<>());
        try (FakeServer fake = new FakeServer((in, out) -> {
            Packet req = in.read();
            seen.add(req);
            out.write(Packet.replyTo(req, PacketType.LOGIN_OK)
                    .setText("kim|김철수|true\n잘못된 줄\nlee|이영희|false\n"));
            in.read(); // 클라이언트가 닫을 때까지
        })) {
            Recorder rec = new Recorder();
            Connection c = Connection.open(fake.address(), rec);
            ServerApi.LoginResult r = new ServerApi(c).login("kim", "pass".toCharArray());
            Packet req = seen.get(0);
            check("LOGIN 요청: reqId·버전·아이디·비밀번호를 담아 보냄",
                    req.getType() == PacketType.LOGIN && req.getReqId() > 0
                            && String.valueOf(ProtocolInfo.VERSION).equals(req.get(HeaderKeys.VERSION))
                            && "kim".equals(req.get(HeaderKeys.USER_ID)) && "pass".equals(req.get(HeaderKeys.PASSWORD)));
            check("LOGIN_OK → 성공, 사용자 목록(잘못된 줄은 건너뜀)",
                    r.isOk() && r.getUsers().size() == 2 && r.getUsers().get(0).getName().equals("김철수")
                            && r.getUsers().get(0).isOnline() && !r.getUsers().get(1).isOnline());
            check("화면 표시: ● 접속 / ○ 미접속",
                    r.getUsers().get(0).toString().equals("● 김철수 (kim)") && r.getUsers().get(1).toString().startsWith("○"));
            c.close();
        }

        try (FakeServer fake = new FakeServer((in, out) -> {
            Packet req = in.read();
            out.write(Packet.replyTo(req, PacketType.LOGIN_FAIL).put(HeaderKeys.REASON, "아이디 또는 비밀번호가 틀렸습니다."));
            in.read();
        })) {
            Connection c = Connection.open(fake.address(), new Recorder());
            ServerApi.LoginResult r = new ServerApi(c).login("kim", "x".toCharArray());
            check("LOGIN_FAIL → 실패, 서버가 준 이유 그대로", !r.isOk() && r.getFailReason().contains("틀렸습니다"));
            c.close();
        }
    }

    // ===================================================================
    private static void disconnects() throws Exception {
        try (FakeServer fake = new FakeServer((in, out) -> {
            out.write(new Packet(PacketType.BYE).put(HeaderKeys.REASON, "다른 곳에서 같은 아이디로 로그인하여 연결이 끊겼습니다."));
        })) {
            Recorder rec = new Recorder();
            Connection.open(fake.address(), rec);
            check("BYE → 서버가 알려 준 이유로 끊김 알림 (U30)",
                    rec.awaitDisconnect() && rec.byServer && rec.reason.contains("다른 곳에서"));
        }

        try (FakeServer fake = new FakeServer((in, out) -> { /* 바로 닫음 */ })) {
            Recorder rec = new Recorder();
            Connection.open(fake.address(), rec);
            check("서버가 그냥 닫음 → '서버와 연결이 끊겼습니다'",
                    rec.awaitDisconnect() && !rec.byServer && Connection.LOST_MESSAGE.equals(rec.reason));
        }

        try (FakeServer fake = new FakeServer((in, out) -> Thread.sleep(2000))) {
            Recorder rec = new Recorder();
            Connection.open(fake.address(), rec, 1000, 300, 10_000); // 서버 침묵 판정을 0.3초로
            check("서버가 아무것도 안 보냄 → 침묵으로 끊김 판정 (U32)",
                    rec.awaitDisconnect() && Connection.SILENT_MESSAGE.equals(rec.reason));
            check("끊김 알림은 한 번만", rec.disconnectCount == 1);
        }

        try (FakeServer fake = new FakeServer((in, out) -> {
            in.read();
            Thread.sleep(2000); // 응답하지 않음
        })) {
            Recorder rec = new Recorder();
            Connection c = Connection.open(fake.address(), rec, 1000, 60_000, 300); // 요청 제한 0.3초
            String message = null;
            try {
                new ServerApi(c).login("kim", "pass".toCharArray());
            } catch (IOException e) {
                message = e.getMessage();
            }
            check("응답이 제한 시간 안에 없음 → 요청 실패 (U10)", ServerApi.TIMEOUT_MESSAGE.equals(message));
            c.close();
        }
    }

    // ===================================================================
    private static void logoutHeartbeatPush() throws Exception {
        List<Packet> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch serverDone = new CountDownLatch(1);
        try (FakeServer fake = new FakeServer((in, out) -> {
            try {
                while (true) {
                    Packet p = in.read();
                    received.add(p);
                    if (p.getType() == PacketType.PING) {
                        out.write(Packet.replyTo(p, PacketType.PONG));
                        if (received.stream().filter(x -> x.getType() == PacketType.PING).count() == 2) {
                            out.write(new Packet(PacketType.MSG).put("messageId", "m1"));
                        }
                    }
                }
            } finally {
                serverDone.countDown();
            }
        })) {
            Recorder rec = new Recorder();
            Connection c = Connection.open(fake.address(), rec);
            ServerApi api = new ServerApi(c);
            Heartbeat hb = new Heartbeat(api, 100); // 테스트에서는 0.1초마다
            long deadline = System.currentTimeMillis() + 3000;
            while (rec.packets.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            hb.stop();
            check("하트비트: 일정 간격으로 PING을 보냄",
                    received.stream().filter(x -> x.getType() == PacketType.PING).count() >= 2);
            check("PONG은 버리고, 서버가 먼저 보낸 MSG만 Listener로 전달",
                    rec.packets.size() == 1 && rec.packets.get(0).getType() == PacketType.MSG);

            api.logout();
            c.close();
            c.awaitClosed(1000);
            serverDone.await(3, TimeUnit.SECONDS);
            check("로그아웃: LOGOUT을 보낸 뒤 연결을 닫음",
                    received.stream().anyMatch(x -> x.getType() == PacketType.LOGOUT));
            Thread.sleep(200);
            check("우리가 닫은 경우에는 끊김 알림을 보내지 않음", rec.disconnectCount == 0);
            check("닫은 뒤에는 보낼 수 없음", !c.send(new Packet(PacketType.PING)));
        }
    }

    // ===================================================================
    /** 연결 하나를 받아 각본대로 움직이는 가짜 서버. */
    private static final class FakeServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;

        FakeServer(Script script) throws IOException {
            server = new ServerSocket(0);
            thread = new Thread(() -> {
                try (Socket s = server.accept()) {
                    script.play(new PacketReader(s.getInputStream()), new PacketWriter(s.getOutputStream()));
                } catch (Exception ignored) {
                    // 클라이언트가 닫거나 각본이 끝남
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        ServerAddress address() {
            return new ServerAddress("localhost", server.getLocalPort());
        }

        @Override
        public void close() throws IOException {
            server.close();
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void check(String name, boolean ok) {
        check.accept(name, ok);
    }
}
