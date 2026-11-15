package coolmsg.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import coolmsg.common.ProtocolInfo;
import coolmsg.common.file.TimeFormat;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketReader;
import coolmsg.common.net.PacketType;
import coolmsg.common.net.PacketWriter;
import coolmsg.server.config.ServerConfig;
import coolmsg.server.net.CloseReason;
import coolmsg.server.net.RequestDispatcher;
import coolmsg.server.net.SendQueue;
import coolmsg.server.net.ServerListener;
import coolmsg.server.net.SessionRegistry;
import coolmsg.server.service.LoginService;

/**
 * 구현 2단계 테스트: 첫 연결 (PING), 접속 받기, 보내기 대기열, 자원 한도.
 *
 * 실제 소켓으로 서버(ServerListener)에 접속해 확인한다. 포트는 0(빈 포트 자동 선택)을 쓰고,
 * 시간 제한은 짧게 줄여서(예: 90초 → 0.5초) 테스트가 오래 걸리지 않게 한다.
 * ServerTest.main에서 불린다.
 */
final class NetTest {

    private interface Action {
        void run() throws Exception;
    }

    private static BiConsumer<String, Boolean> check;

    private NetTest() {
    }

    static void run(BiConsumer<String, Boolean> checker, Consumer<String> section) throws Exception {
        check = checker;

        section.accept("LoginService - PING → PONG");
        loginServicePing();

        section.accept("ServerListener - 실제 소켓으로 PING·오류 응답");
        pingOverSocket();

        section.accept("ServerListener - 연결을 끊는 경우 (형식 오류, 시간 초과, 접속 수 초과)");
        closingCases();

        section.accept("ServerListener - 중지·재시작, 포트 사용 중");
        stopAndRestart();

        section.accept("SendQueue - 대기열 가득 (U28)");
        sendQueueOverflow();
    }

    // ===================================================================
    private static void loginServicePing() throws Exception {
        LoginService service = new LoginService(ServerConfig.defaults());
        Packet pong = service.ping(new Packet(PacketType.PING).setReqId(7));
        check("PONG: reqId 그대로, 서버 이름·버전",
                pong.getType() == PacketType.PONG && pong.getReqId() == 7
                        && "CoolMsg 서버".equals(pong.get(HeaderKeys.SERVER_NAME))
                        && String.valueOf(ProtocolInfo.VERSION).equals(pong.get(HeaderKeys.VERSION)));
        check("PONG: 서버 시각은 U2 형식", TimeFormat.parse(pong.get(HeaderKeys.SERVER_TIME)) != null);
    }

    // ===================================================================
    private static void pingOverSocket() throws Exception {
        TestServer server = TestServer.start(60_000, SendQueue.DEFAULT_CAPACITY, 10);
        try (Client c = new Client(server.port())) {
            c.send(new Packet(PacketType.PING).setReqId(1));
            Packet pong = c.read();
            check("PING → PONG (reqId 1 그대로)", pong.getType() == PacketType.PONG && pong.getReqId() == 1);

            c.sendRaw("type=SNED\nreqId=2\n");
            Packet unknown = c.read();
            check("모르는 명령 → ERROR, reqId 유지",
                    unknown.getType() == PacketType.ERROR && unknown.getReqId() == 2
                            && unknown.get(HeaderKeys.REASON).contains("알 수 없는 명령"));

            c.send(new Packet(PacketType.PONG).setReqId(3));
            Packet serverOnly = c.read();
            check("서버가 보내는 종류(PONG)를 보내면 → ERROR 알 수 없는 명령",
                    serverOnly.getType() == PacketType.ERROR && serverOnly.getReqId() == 3);

            c.send(new Packet(PacketType.SEND).setReqId(4));
            Packet needLogin = c.read();
            check("로그인 전 SEND → ERROR '로그인이 필요합니다'",
                    needLogin.getType() == PacketType.ERROR && needLogin.getReqId() == 4
                            && needLogin.get(HeaderKeys.REASON).contains("로그인이 필요"));

            c.send(new Packet(PacketType.PING).setReqId(5));
            check("오류 응답 뒤에도 연결은 유지되어 PING이 계속 됨", c.read().getReqId() == 5);
            check("연결 열림이 로그에 남음", server.logs().stream().anyMatch(l -> l.startsWith("연결 열림")));
        } finally {
            server.stop();
        }
    }

    // ===================================================================
    private static void closingCases() throws Exception {
        TestServer server = TestServer.start(500, SendQueue.DEFAULT_CAPACITY, 2);
        try {
            try (Client c = new Client(server.port())) {
                c.sendRawLength(0); // 헤더 길이 0 → 형식 오류
                expectThrows("형식 오류 패킷 → 서버가 연결을 끊음 (클라이언트는 EOF)", EOFException.class, c::read);
            }
            // 세션 목록에서 빠진 "뒤에" 로그가 남으므로, 목록이 아니라 확인하려는 로그 자체를 기다린다
            waitUntil(() -> server.logs().stream().anyMatch(l -> l.contains(CloseReason.FORMAT_ERROR.getDescription())));
            check("형식 오류가 로그에 이유와 함께 남음",
                    server.logs().stream().anyMatch(l -> l.contains(CloseReason.FORMAT_ERROR.getDescription())));

            try (Client c = new Client(server.port())) {
                expectThrows("0.5초 동안 아무것도 안 보내면 → 서버가 끊음 (하트비트 끊김)", EOFException.class, c::read);
            }
            waitUntil(() -> server.logs().stream().anyMatch(l -> l.contains(CloseReason.IDLE_TIMEOUT.getDescription())));
            waitUntil(() -> server.registry.size() == 0);
            check("시간 초과가 로그에 남음",
                    server.logs().stream().anyMatch(l -> l.contains(CloseReason.IDLE_TIMEOUT.getDescription())));

            Client a = new Client(server.port());
            Client b = new Client(server.port());
            a.send(new Packet(PacketType.PING).setReqId(1));
            b.send(new Packet(PacketType.PING).setReqId(1));
            a.read();
            b.read(); // 두 연결 모두 등록된 뒤
            try (Client third = new Client(server.port())) {
                Packet rejected = third.read();
                check("동시 접속 한도(테스트 2개) 초과 → ERROR '접속자가 너무 많습니다…'",
                        rejected.getType() == PacketType.ERROR
                                && ServerListener.TOO_MANY_MESSAGE.equals(rejected.get(HeaderKeys.REASON)));
                expectThrows("그리고 바로 끊음", EOFException.class, third::read);
            }
            a.close();
            b.close();
        } finally {
            server.stop();
        }
    }

    // ===================================================================
    private static void stopAndRestart() throws Exception {
        int port = freePort();
        TestServer server = TestServer.startOn(port, 60_000, SendQueue.DEFAULT_CAPACITY, 10);
        Client c = new Client(port);
        c.send(new Packet(PacketType.PING).setReqId(1));
        c.read();
        server.listener.stop();
        expectThrows("서버 중지 → 접속 중인 클라이언트 연결이 끊김 (U8)", EOFException.class, c::read);
        c.close();
        waitUntil(() -> server.registry.size() == 0);
        check("중지 후 세션 0개, 실행 중 아님, 상태 변경 알림",
                server.registry.size() == 0 && !server.listener.isRunning() && server.states().equals(List.of(true, false)));

        server.listener.start();
        try (Client again = new Client(port)) {
            again.send(new Packet(PacketType.PING).setReqId(9));
            check("같은 포트로 바로 다시 시작해 PING 성공", again.read().getReqId() == 9);
        } finally {
            server.stop();
        }

        try (ServerSocket occupied = new ServerSocket(0)) {
            TestServer clash = TestServer.create(occupied.getLocalPort(), 60_000, SendQueue.DEFAULT_CAPACITY, 10);
            expectThrows("포트가 이미 사용 중 → BindException (관리 화면은 중지 상태로 열림, U25)",
                    BindException.class, clash.listener::start);
            check("시작 실패 시 실행 중이 아님", !clash.listener.isRunning());
        }
    }

    // ===================================================================
    private static void sendQueueOverflow() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        OutputStream stuck = new OutputStream() { // 상대가 읽지 않아 쓰기가 멈춘 상황
            @Override
            public void write(int b) throws IOException {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
        };
        List<CloseReason> problems = Collections.synchronizedList(new ArrayList<>());
        SendQueue q = new SendQueue(stuck, 3, "send-test", problems::add);
        q.start();
        q.offer(new Packet(PacketType.PING)); // 보내기 스레드가 꺼내 쓰다가 멈춤
        waitUntil(() -> q.size() == 0);
        boolean ok = q.offer(new Packet(PacketType.PING)) && q.offer(new Packet(PacketType.PING))
                && q.offer(new Packet(PacketType.PING));
        check("용량(테스트 3)까지는 넣을 수 있음", ok && q.size() == 3);
        check("가득 차면 offer가 false, QUEUE_FULL 알림 (연결을 끊게 함)",
                !q.offer(new Packet(PacketType.PING)) && problems.equals(List.of(CloseReason.QUEUE_FULL)));
        q.close();
        check("닫은 뒤에는 넣을 수 없음", !q.offer(new Packet(PacketType.PING)));
        release.countDown();
    }

    // ===================================================================
    // 도우미
    // ===================================================================

    /** 테스트용 서버: 사건(로그·상태)을 모아 둔다. */
    private static final class TestServer implements ServerEventListener {
        final SessionRegistry registry = new SessionRegistry();
        final ServerListener listener;
        private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        private final List<Boolean> states = Collections.synchronizedList(new ArrayList<>());

        private TestServer(int port, int idleMillis, int queueCapacity, int maxConnections) {
            RequestDispatcher dispatcher = new RequestDispatcher(new LoginService(ServerConfig.defaults()));
            listener = new ServerListener(port, dispatcher, registry, this, idleMillis, queueCapacity, maxConnections);
        }

        static TestServer create(int port, int idle, int cap, int max) {
            return new TestServer(port, idle, cap, max);
        }

        static TestServer start(int idle, int cap, int max) throws IOException {
            return startOn(0, idle, cap, max);
        }

        static TestServer startOn(int port, int idle, int cap, int max) throws IOException {
            TestServer s = new TestServer(port, idle, cap, max);
            s.listener.start();
            return s;
        }

        int port() {
            return listener.getLocalPort();
        }

        void stop() {
            listener.stop();
        }

        List<String> logs() {
            synchronized (logs) {
                return new ArrayList<>(logs);
            }
        }

        List<Boolean> states() {
            synchronized (states) {
                return new ArrayList<>(states);
            }
        }

        @Override
        public void onLog(String message) {
            logs.add(message);
        }

        @Override
        public void onServerStateChanged(boolean running) {
            states.add(running);
        }
    }

    /** 테스트용 클라이언트 소켓. 응답을 3초까지만 기다린다. */
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final PacketWriter writer;
        private final PacketReader reader;

        Client(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(3000);
            writer = new PacketWriter(socket.getOutputStream());
            reader = new PacketReader(socket.getInputStream());
        }

        void send(Packet p) throws IOException {
            writer.write(p);
        }

        /** PacketWriter를 거치지 않고 헤더를 직접 써서, 모르는 type 같은 패킷을 만든다. */
        void sendRaw(String header) throws IOException {
            java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
            byte[] h = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(h.length);
            out.write(h);
            out.writeInt(0);
            out.flush();
        }

        void sendRawLength(int headerLength) throws IOException {
            java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
            out.writeInt(headerLength);
            out.flush();
        }

        Packet read() throws IOException {
            return reader.read();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    /** 다른 스레드의 일이 끝나기를 최대 3초 기다린다. */
    private static void waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.holds() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private static void check(String name, boolean ok) {
        check.accept(name, ok);
    }

    private static void expectThrows(String name, Class<? extends Throwable> expected, Action action) {
        try {
            action.run();
            check(name + "  (예외가 나지 않음)", false);
        } catch (SocketTimeoutException t) {
            check(name + "  (3초 안에 응답 없음)", expected.isInstance(t));
        } catch (Throwable t) {
            boolean ok = expected.isInstance(t);
            check(ok ? name : name + "  (다른 예외: " + t + ")", ok);
        }
    }
}
