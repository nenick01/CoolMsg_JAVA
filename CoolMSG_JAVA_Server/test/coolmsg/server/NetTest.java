package coolmsg.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import coolmsg.server.config.UserDirectory;
import coolmsg.server.net.CloseReason;
import coolmsg.server.net.RequestDispatcher;
import coolmsg.server.net.SendQueue;
import coolmsg.server.net.ServerListener;
import coolmsg.server.net.SessionRegistry;
import coolmsg.server.net.StorageHooks;
import coolmsg.server.service.LoginService;
import coolmsg.server.service.MessageService;
import coolmsg.server.store.MessageStore;
import coolmsg.server.store.PendingIndex;
import coolmsg.server.store.ReceiptQueue;

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

        section.accept("로그인 (구현 3단계)");
        login();

        section.accept("같은 아이디 재로그인·로그아웃·강제 종료(BYE) (C3, C5, U30, U31)");
        duplicateLogoutKick();

        section.accept("메시지 보내기·받기 (구현 4단계, 실제 소켓)");
        messaging();

        section.accept("밀린 메시지 속도 조절 — 대기열 가득으로 끊기지 않음 (U36)");
        pendingPacing();

        section.accept("서버 중지·재시작 뒤에도 밀린 메시지 유지 (3.8.6)");
        restartKeepsPending();

        section.accept("구현 5단계 - 접속 상태(PRESENCE)·읽음 알림(RECEIPT) 실제 소켓 (2.5, 2.6)");
        presenceAndReceipts();
    }

    // ===================================================================
    // 구현 4단계
    // ===================================================================

    private static Packet sendPacket(String to, String body) {
        return new Packet(PacketType.SEND).put(HeaderKeys.MESSAGE_ID, java.util.UUID.randomUUID().toString())
                .put("receivers", to).setText(body);
    }

    private static Client loggedIn(TestServer server, String id) throws Exception {
        Client c = new Client(server.port());
        c.send(loginPacket(id, "pass").setReqId(1));
        Packet ok = c.read();
        if (ok.getType() != PacketType.LOGIN_OK) {
            throw new IllegalStateException("로그인 실패: " + ok);
        }
        return c;
    }

    private static void messaging() throws Exception {
        TestServer server = TestServer.start(60_000, SendQueue.DEFAULT_CAPACITY, 10);
        try (Client kim = loggedIn(server, "kim")) {
            Packet offline = sendPacket("lee", "오프라인 lee에게").setReqId(2);
            kim.send(offline);
            Packet ok = kim.read();
            check.accept("오프라인 사람에게 SEND → SEND_OK (서버에 저장)",
                    ok.getType() == PacketType.SEND_OK && ok.getReqId() == 2
                            && offline.get(HeaderKeys.MESSAGE_ID).equals(ok.get(HeaderKeys.MESSAGE_ID)));
            check.accept("서버 로그 '메시지 저장: kim → lee'",
                    waitUntil(() -> server.logs().contains("메시지 저장: kim → lee")));

            try (Client lee = new Client(server.port())) {
                lee.send(loginPacket("lee", "pass").setReqId(1));
                Packet loginOk = lee.read();
                Packet msg = lee.read();
                check.accept("lee 로그인 → LOGIN_OK 다음에 밀린 MSG",
                        loginOk.getType() == PacketType.LOGIN_OK && msg.getType() == PacketType.MSG
                                && "kim".equals(msg.get("sender")) && "오프라인 lee에게".equals(msg.getText()));
                lee.send(new Packet(PacketType.ACK).put(HeaderKeys.MESSAGE_ID, msg.get(HeaderKeys.MESSAGE_ID)));

                Packet live = sendPacket("lee", "접속 중인 lee에게").setReqId(3);
                kim.send(live);
                check.accept("접속 중에 SEND → kim은 SEND_OK", kim.read().getType() == PacketType.SEND_OK);
                Packet liveMsg = lee.read();
                check.accept("lee는 바로 MSG를 받음",
                        liveMsg.getType() == PacketType.MSG && "접속 중인 lee에게".equals(liveMsg.getText()));
                lee.send(new Packet(PacketType.ACK).put(HeaderKeys.MESSAGE_ID, liveMsg.get(HeaderKeys.MESSAGE_ID)));

                kim.send(sendPacket("nobody", "x").setReqId(4));
                Packet fail = kim.read();
                check.accept("없는 받는 사람 → SEND_FAIL, 연결은 유지",
                        fail.getType() == PacketType.SEND_FAIL && fail.getReqId() == 4);
                lee.send(new Packet(PacketType.LOGOUT));
                expectThrows("lee 로그아웃", EOFException.class, lee::read);
            }
            check.accept("ACK가 처리됨 → lee의 색인이 빔",
                    waitUntil(() -> readQuietly(server.dataDir.resolve("pending/lee.txt")).isEmpty()));
            try (Client lee = loggedIn(server, "lee")) {
                check.accept("ACK한 메시지는 다시 로그인해도 오지 않음", lee.readOrNull(300) == null);
            }
        } finally {
            server.stop();
        }
    }

    private static void pendingPacing() throws Exception {
        int capacity = 4; // 절반 = 2개
        int count = 30;
        TestServer server = TestServer.start(60_000, capacity, 10);
        try {
            try (Client kim = loggedIn(server, "kim")) {
                for (int i = 0; i < count; i++) {
                    kim.send(sendPacket("lee", "메시지 " + i).setReqId(10 + i));
                    kim.read();
                }
            }
            try (Client lee = new Client(server.port())) {
                lee.send(loginPacket("lee", "pass").setReqId(1));
                Thread.sleep(300); // 일부러 늦게 읽기 시작: 속도 조절이 없으면 대기열(4개)이 넘쳐 끊긴다
                Packet ok = lee.read();
                int received = 0;
                boolean inOrder = true;
                for (int i = 0; i < count; i++) {
                    Packet msg = lee.read();
                    if (msg.getType() != PacketType.MSG) {
                        break;
                    }
                    inOrder &= ("메시지 " + i).equals(msg.getText());
                    received++;
                }
                check.accept("밀린 메시지 " + count + "개 > 대기열 " + capacity + "개여도 모두 받음 (끊기지 않음)",
                        ok.getType() == PacketType.LOGIN_OK && received == count);
                check.accept("보낸 순서대로 받음", inOrder);
                check.accept("대기열 가득으로 끊긴 기록 없음",
                        server.logs().stream().noneMatch(l -> l.contains(CloseReason.QUEUE_FULL.getDescription())));
            }
        } finally {
            server.stop();
        }
    }

    private static void restartKeepsPending() throws Exception {
        Path data = TestServer.newTempDir("coolmsg-net-restart");
        TestServer first = new TestServer(0, 60_000, SendQueue.DEFAULT_CAPACITY, 10, data);
        first.listener.start();
        try (Client kim = loggedIn(first, "kim")) {
            kim.send(sendPacket("lee", "재시작 전에 보냄").setReqId(2));
            kim.read();
        }
        first.listener.stop();
        first.listener.awaitStopped(5000);
        check.accept("중지 → 연결이 다 닫힌 뒤 정상 종료 표시(.clean)",
                Files.exists(data.resolve("pending/.clean")));

        TestServer second = new TestServer(0, 60_000, SendQueue.DEFAULT_CAPACITY, 10, data);
        second.listener.start();
        try (Client lee = new Client(second.port())) {
            check.accept("다시 시작 → 색인 그대로 사용", second.logs().stream().anyMatch(l -> l.contains("그대로")));
            lee.send(loginPacket("lee", "pass").setReqId(1));
            lee.read();
            Packet msg = lee.read();
            check.accept("재시작 뒤 로그인 → 밀린 메시지 받음",
                    msg.getType() == PacketType.MSG && "재시작 전에 보냄".equals(msg.getText()));
        } finally {
            second.stop();
        }
    }

    private static void presenceAndReceipts() throws Exception {
        TestServer server = TestServer.start(60_000, SendQueue.DEFAULT_CAPACITY, 10);
        try (Client kim = loggedIn(server, "kim")) {
            String id;
            try (Client lee = loggedIn(server, "lee")) {
                Packet presence = kim.readAny();
                check.accept("lee 로그인 → kim에게 PRESENCE(lee, true)",
                        presence.getType() == PacketType.PRESENCE && "lee".equals(presence.get(HeaderKeys.USER_ID))
                                && "true".equals(presence.get(HeaderKeys.ONLINE)));
                check.accept("로그인한 본인에게는 자기 PRESENCE가 오지 않음", lee.readOrNull(200) == null);

                Packet send = sendPacket("lee", "읽음 확인용");
                id = send.get(HeaderKeys.MESSAGE_ID);
                kim.send(send.setReqId(2));
                kim.read(); // SEND_OK
                Packet msg = lee.read();
                lee.send(new Packet(PacketType.ACK).put(HeaderKeys.MESSAGE_ID, msg.get(HeaderKeys.MESSAGE_ID)));
                Packet delivered = kim.readAny();
                lee.send(new Packet(PacketType.READ).put(HeaderKeys.MESSAGE_ID, id));
                Packet read = kim.readAny();
                check.accept("lee의 ACK·READ → kim에게 RECEIPT(DELIVERED) → RECEIPT(READ)",
                        delivered.getType() == PacketType.RECEIPT && "DELIVERED".equals(delivered.get(HeaderKeys.STATE))
                                && read.getType() == PacketType.RECEIPT && "READ".equals(read.get(HeaderKeys.STATE)));
                kim.send(new Packet(PacketType.STATUS).setReqId(3).put(HeaderKeys.MESSAGE_ID, id));
                Packet status = kim.read();
                check.accept("STATUS → state.lee=READ", status.getType() == PacketType.STATUS_OK
                        && "READ".equals(status.get("state.lee")));
            }
            Packet off = kim.readAny();
            check.accept("lee 연결이 닫힘 → kim에게 PRESENCE(lee, false)",
                    off.getType() == PacketType.PRESENCE && "false".equals(off.get(HeaderKeys.ONLINE)));
            try (Client lee1 = loggedIn(server, "lee")) {
                kim.readAny(); // PRESENCE(lee, true)
                try (Client lee2 = loggedIn(server, "lee")) {
                    lee1.read(); // BYE
                    check.accept("같은 아이디가 다른 곳에서 다시 로그인 → 접속 상태 알림 없음 (계속 접속 중)",
                            kim.readOrNull(300) == null && server.registry.isOnline("lee") && lee2 != null);
                }
            }
        } finally {
            server.stop();
        }
    }

    /** 파일 내용 (없으면 빈 문자열). */
    private static String readQuietly(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file).strip() : "";
        } catch (IOException e) {
            return "(읽기 실패)";
        }
    }

    // ===================================================================
    private static void loginServicePing() throws Exception {
        LoginService service = new LoginService(ServerConfig.defaults(), TestServer.users());
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
        Packet bye = c.read();
        check("서버 중지 → 로그인 전 연결도 BYE('서버가 중지되었습니다')를 먼저 받음 (U30)",
                bye.getType() == PacketType.BYE && ServerListener.SERVER_STOP_MESSAGE.equals(bye.get(HeaderKeys.REASON)));
        expectThrows("이어서 연결이 끊김 (U8)", EOFException.class, c::read);
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
    private static Packet loginPacket(String id, String pw) {
        return new Packet(PacketType.LOGIN).put(HeaderKeys.VERSION, ProtocolInfo.VERSION)
                .put(HeaderKeys.USER_ID, id).put(HeaderKeys.PASSWORD, pw);
    }

    private static void login() throws Exception {
        TestServer server = TestServer.start(60_000, SendQueue.DEFAULT_CAPACITY, 10);
        try (Client c = new Client(server.port())) {
            c.send(new Packet(PacketType.LOGIN).setReqId(1).put(HeaderKeys.VERSION, 99)
                    .put(HeaderKeys.USER_ID, "kim").put(HeaderKeys.PASSWORD, "pass"));
            Packet badVersion = c.read();
            check("버전이 다름 → LOGIN_FAIL (버전 안내)", badVersion.getType() == PacketType.LOGIN_FAIL
                    && badVersion.get(HeaderKeys.REASON).contains("버전"));

            c.send(loginPacket("kim", "wrong").setReqId(2));
            Packet wrongPw = c.read();
            c.send(loginPacket("nobody", "pass").setReqId(3));
            Packet noUser = c.read();
            c.send(loginPacket("park", "pass").setReqId(4));
            Packet deleted = c.read();
            c.send(new Packet(PacketType.LOGIN).setReqId(5).put(HeaderKeys.VERSION, ProtocolInfo.VERSION)
                    .put(HeaderKeys.USER_ID, "kim"));
            Packet noPw = c.read();
            check("틀린 비밀번호·없는 아이디·삭제된 아이디·비밀번호 없음 → 모두 같은 문구 (A1)",
                    LoginService.WRONG_CREDENTIALS.equals(wrongPw.get(HeaderKeys.REASON))
                            && LoginService.WRONG_CREDENTIALS.equals(noUser.get(HeaderKeys.REASON))
                            && LoginService.WRONG_CREDENTIALS.equals(deleted.get(HeaderKeys.REASON))
                            && LoginService.WRONG_CREDENTIALS.equals(noPw.get(HeaderKeys.REASON))
                            && wrongPw.getReqId() == 2 && noUser.getReqId() == 3);
            check("로그인 실패가 서버 로그에 남음", server.logs().stream().anyMatch(l -> l.startsWith("로그인 실패")));

            c.send(loginPacket("kim", "pass").setReqId(6));
            Packet ok = c.read();
            List<String> lines = List.of(ok.getText().split("\n"));
            check("로그인 성공 → LOGIN_OK, 사용자 목록 등록 순서·접속 상태·삭제된 사용자 제외",
                    ok.getType() == PacketType.LOGIN_OK && ok.getReqId() == 6
                            && lines.equals(List.of("kim|김철수|true", "lee|이영희|false")));
            check("로그인 목록 알림에 kim", server.lastOnline().equals(List.of("kim")));

            c.send(new Packet(PacketType.UPLOAD).setReqId(7));
            Packet status = c.read();
            check("로그인 후 UPLOAD → '로그인 필요'가 아니라 '지원하지 않는 기능'(첨부파일은 제출 범위 밖)",
                    status.getType() == PacketType.ERROR && status.get(HeaderKeys.REASON).contains("지원하지 않는"));

            c.send(loginPacket("lee", "pass").setReqId(8));
            Packet again = c.read();
            check("이미 로그인한 연결에서 다시 LOGIN → LOGIN_FAIL '이미 로그인되어 있습니다' (U31)",
                    again.getType() == PacketType.LOGIN_FAIL
                            && LoginService.ALREADY_LOGGED_IN.equals(again.get(HeaderKeys.REASON)));

            try (Client lee = new Client(server.port())) {
                lee.send(loginPacket("lee", "pass").setReqId(1));
                Packet leeOk = lee.read();
                check("두 번째 사용자의 LOGIN_OK에는 먼저 접속한 kim도 접속 중 (C5)",
                        List.of(leeOk.getText().split("\n")).equals(List.of("kim|김철수|true", "lee|이영희|true")));
            }
        } finally {
            server.stop();
        }
    }

    private static void duplicateLogoutKick() throws Exception {
        TestServer server = TestServer.start(60_000, SendQueue.DEFAULT_CAPACITY, 10);
        try {
            Client first = new Client(server.port());
            first.send(loginPacket("kim", "pass").setReqId(1));
            first.read();

            Client second = new Client(server.port());
            second.send(loginPacket("kim", "pass").setReqId(1));
            check("같은 아이디로 다른 곳에서 로그인 → 새 연결 성공", second.read().getType() == PacketType.LOGIN_OK);
            Packet bye = first.read();
            check("기존 연결은 BYE(다른 곳에서 로그인)를 받고 (U30)",
                    bye.getType() == PacketType.BYE
                            && RequestDispatcher.DUPLICATE_LOGIN_MESSAGE.equals(bye.get(HeaderKeys.REASON)));
            expectThrows("이어서 끊김", EOFException.class, first::read);
            first.close();
            waitUntil(() -> server.logs().stream().anyMatch(l -> l.contains(CloseReason.DUPLICATE_LOGIN.getDescription())));
            check("옛 연결이 닫혀도 kim은 계속 접속 중 — 새 연결을 지우지 않음 (C3)",
                    server.registry.isOnline("kim") && server.lastOnline().contains("kim"));

            Client lee = new Client(server.port());
            lee.send(loginPacket("lee", "pass").setReqId(1));
            lee.read();
            lee.send(new Packet(PacketType.LOGOUT));
            expectThrows("LOGOUT → 서버가 연결을 닫음", EOFException.class, lee::read);
            lee.close();
            waitUntil(() -> !server.registry.isOnline("lee"));
            check("로그아웃하면 접속 목록에서 빠짐", !server.registry.isOnline("lee") && !server.lastOnline().contains("lee"));

            server.registry.kick("kim", "관리자가 계정을 삭제하여 연결이 끊겼습니다.", CloseReason.USER_DELETED);
            Packet deletedBye = second.read();
            check("계정 삭제 → BYE(이유) 후 끊김 (U30)",
                    deletedBye.getType() == PacketType.BYE && deletedBye.get(HeaderKeys.REASON).contains("삭제"));
            expectThrows("이어서 끊김", EOFException.class, second::read);
            second.close();
            waitUntil(() -> !server.registry.isOnline("kim"));
            check("끊긴 뒤 접속 목록 비어 있음", server.registry.onlineUserIds().isEmpty());

            Client last = new Client(server.port());
            last.send(loginPacket("lee", "pass").setReqId(1));
            last.read();
            server.listener.stop();
            Packet stopBye = last.read();
            check("서버 중지 → 로그인한 클라이언트도 BYE('서버가 중지되었습니다') 후 끊김 (U8, U30)",
                    stopBye.getType() == PacketType.BYE
                            && ServerListener.SERVER_STOP_MESSAGE.equals(stopBye.get(HeaderKeys.REASON)));
            expectThrows("이어서 끊김", EOFException.class, last::read);
            last.close();
        } finally {
            server.stop();
        }
    }

    // ===================================================================
    // 도우미
    // ===================================================================

    /** 테스트용 서버: 사건(로그·상태)을 모아 둔다. */
    private static final class TestServer implements ServerEventListener {
        final SessionRegistry registry = new SessionRegistry(this);
        private final List<List<String>> online = Collections.synchronizedList(new ArrayList<>());
        final ServerListener listener;
        private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        private final List<Boolean> states = Collections.synchronizedList(new ArrayList<>());

        final Path dataDir;
        final MessageService messages;

        private TestServer(int port, int idleMillis, int queueCapacity, int maxConnections) {
            this(port, idleMillis, queueCapacity, maxConnections, newTempDir("coolmsg-net-data"));
        }

        /** dataDir을 정해서 만든다 (같은 데이터 폴더로 서버를 다시 켜는 테스트용). */
        TestServer(int port, int idleMillis, int queueCapacity, int maxConnections, Path dataDir) {
            UserDirectory users = users();
            this.dataDir = dataDir;
            MessageStore store = new MessageStore(dataDir.resolve("messages"));
            PendingIndex index = new PendingIndex(dataDir.resolve("pending"));
            messages = new MessageService(store, index, new ReceiptQueue(dataDir.resolve("receipts")), users, registry, this);
            RequestDispatcher dispatcher = new RequestDispatcher(new LoginService(ServerConfig.defaults(), users),
                    messages, registry, this);
            listener = new ServerListener(port, dispatcher, registry, this, idleMillis, queueCapacity, maxConnections);
            listener.setStorageHooks(new StorageHooks() {
                @Override
                public void open() throws IOException {
                    messages.openStorage();
                }

                @Override
                public void close() {
                    messages.closeStorage();
                }
            });
        }

        static Path newTempDir(String prefix) {
            try {
                return Files.createTempDirectory(prefix);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
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

        /** kim, lee(비밀번호 "pass")와 삭제된 park이 있는 사용자 목록 (임시 폴더). */
        static UserDirectory users() {
            try {
                Path dir = Files.createTempDirectory("coolmsg-net-users");
                UserDirectory d = new UserDirectory(dir.resolve("users.txt"));
                d.load();
                d.add("kim", "김철수", "pass".toCharArray());
                d.add("lee", "이영희", "pass".toCharArray());
                d.add("park", "박민수", "pass".toCharArray());
                d.delete("park");
                return d;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** 마지막으로 알려진 로그인 목록. */
        List<String> lastOnline() {
            synchronized (online) {
                return online.isEmpty() ? List.of() : online.get(online.size() - 1);
            }
        }

        @Override
        public void onOnlineUsersChanged(List<String> ids) {
            online.add(ids);
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

        /** 다음 패킷. 다른 사람 때문에 끼어드는 PRESENCE·RECEIPT(5단계)는 건너뛴다. */
        Packet read() throws IOException {
            while (true) {
                Packet p = reader.read();
                if (p.getType() != PacketType.PRESENCE && p.getType() != PacketType.RECEIPT) {
                    return p;
                }
            }
        }

        /** 건너뛰지 않고 그대로 다음 패킷 (5단계 PRESENCE·RECEIPT 검사용). */
        Packet readAny() throws IOException {
            return reader.read();
        }

        /** millis 안에 오는 패킷. 아무것도 안 오면 null. */
        Packet readOrNull(int millis) throws IOException {
            socket.setSoTimeout(millis);
            try {
                return reader.read();
            } catch (SocketTimeoutException e) {
                return null;
            } finally {
                socket.setSoTimeout(3000);
            }
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

    /** 다른 스레드의 일이 끝나기를 최대 3초 기다린다. 결국 조건이 맞았는지 돌려준다. */
    private static boolean waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.holds() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return condition.holds();
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
