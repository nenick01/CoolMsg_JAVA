package coolmsg.client;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import coolmsg.client.model.MailItem;
import coolmsg.client.net.Connection;
import coolmsg.client.net.ServerAddress;
import coolmsg.client.net.ServerApi;
import coolmsg.client.store.ClientPaths;
import coolmsg.client.store.Mailbox;
import coolmsg.common.file.TimeFormat;
import coolmsg.common.model.HtmlText;
import coolmsg.common.model.MessageData;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketReader;
import coolmsg.common.net.PacketType;
import coolmsg.common.net.PacketWriter;

/**
 * 구현 4단계 클라이언트 테스트: 보관함, 보내기 요청, 새 메시지 "저장 → ACK" 순서 (설계서 4.1, 4.2, U38).
 * 가짜 서버(각본대로 응답)를 쓴다. ClientTest.main에서 불린다.
 */
final class MessageClientTest {

    private static BiConsumer<String, Boolean> check;

    private MessageClientTest() {
    }

    static void run(BiConsumer<String, Boolean> checker, Consumer<String> section) throws Exception {
        check = checker;

        section.accept("Mailbox - 받은·보낸 내역, 읽음, 삭제 (4.2, U38)");
        mailbox();

        section.accept("ServerApi - SEND 요청과 SEND_OK/SEND_FAIL");
        sendRequest();

        section.accept("ClientController - 새 메시지: 저장 → ACK, 중복·지운 메시지도 ACK만 (4.1, U38)");
        receiveAndAck();

        section.accept("ClientController - 보내기 전 검사 (U5)");
        draftCheck();

        section.accept("WrapLayout - 작성 창 받는 사람 칸 줄 바꿈 (버그 수정)");
        wrapLayout();

        section.accept("구현 5단계 - 보낸 내역 상태(앞으로만)·읽음 요약·READ 다시 보내기 (2.6, U4, U49)");
        receiptsAndReads();

        section.accept("구현 6단계 - 체크박스 조직도·ORG 요청 (2.10, U53)");
        orgCheckTree();
    }

    // ===================================================================

    private static MessageData received(String from, LocalDateTime at, String body) {
        MessageData d = new MessageData(UUID.randomUUID().toString(), List.of("lee"), List.of(), body);
        d.setSender(from);
        d.setSentAt(at);
        return d;
    }

    private static void mailbox() throws Exception {
        Path dir = Files.createTempDirectory("coolmsg-mailbox");
        Mailbox box = new Mailbox(dir);
        check.accept("처음엔 비어 있음", box.inbox().isEmpty() && box.sent().isEmpty() && box.unreadCount() == 0);

        MessageData older = received("kim", LocalDateTime.of(2026, 11, 23, 9, 0, 0), "먼저");
        MessageData newer = received("park", LocalDateTime.of(2026, 11, 23, 15, 30, 0), "나중");
        check.accept("새 메시지 저장 → SAVED", box.saveReceived(older) == Mailbox.ReceiveResult.SAVED);
        box.saveReceived(newer);
        check.accept("같은 메시지 다시 → DUPLICATE (저장 안 함)",
                box.saveReceived(older) == Mailbox.ReceiveResult.DUPLICATE && box.inbox().size() == 2);
        List<MailItem> inbox = box.inbox();
        check.accept("최신이 위 (U42)", inbox.get(0).getMessageId().equals(newer.getMessageId()));
        check.accept("날짜·시간 표시 yyyy-MM-dd HH:mm", inbox.get(0).sentAtText().equals("2026-11-23 15:30"));
        check.accept("안 읽은 수 2", box.unreadCount() == 2 && !inbox.get(0).isRead());

        check.accept("읽음 표시: 처음 → true", box.markRead(newer.getMessageId()));
        check.accept("읽음 표시: 두 번째 → false (READ는 한 번만)", !box.markRead(newer.getMessageId()));
        check.accept("안 읽은 수 1", box.unreadCount() == 1);

        box.deleteReceived(older.getMessageId());
        check.accept("받은 메시지 삭제 → 파일 없음, deleted.txt에 ID만",
                box.inbox().size() == 1 && !Files.exists(dir.resolve("inbox/" + older.getMessageId() + ".txt"))
                        && Files.readString(dir.resolve("deleted.txt")).strip().equals(older.getMessageId()));
        check.accept("지운 메시지가 다시 와도 되살아나지 않음 (U38)",
                box.saveReceived(older) == Mailbox.ReceiveResult.DELETED_BEFORE && box.inbox().size() == 1);
        Mailbox reopened = new Mailbox(dir);
        check.accept("프로그램을 다시 켜도 지운 기록 유지",
                reopened.saveReceived(older) == Mailbox.ReceiveResult.DELETED_BEFORE);

        MessageData sent = new MessageData(UUID.randomUUID().toString(), List.of("lee", "park"), List.of(),
                HtmlText.fromPlain("보냄"));
        sent.setSender("kim");
        sent.setSentAt(TimeFormat.now());
        box.saveSent(sent);
        check.accept("보낸 내역 저장·목록", box.sent().size() == 1
                && box.sent().get(0).getData().getReceivers().equals(List.of("lee", "park")));
        box.deleteSent(sent.getMessageId());
        check.accept("보낸 메시지 삭제", box.sent().isEmpty());

        MessageData evil = new MessageData("../../evil", List.of("lee"), List.of(), "x");
        boolean rejected;
        try {
            box.saveReceived(evil);
            rejected = false;
        } catch (IOException e) {
            rejected = true;
        }
        check.accept("UUID가 아닌 ID는 저장 거부 (경로 조작 방지)", rejected && !Files.exists(dir.resolve("../evil.txt")));

        Files.writeString(dir.resolve("inbox/" + UUID.randomUUID() + ".txt"), "손상된 파일");
        check.accept("손상된 파일은 목록에서 건너뜀", box.inbox().size() == 1);
    }

    private static void sendRequest() throws Exception {
        List<Packet> seen = Collections.synchronizedList(new ArrayList<>());
        try (FakeServer server = new FakeServer((in, out) -> {
            Packet first = in.read();
            seen.add(first);
            out.write(Packet.replyTo(first, PacketType.SEND_OK)
                    .put(HeaderKeys.MESSAGE_ID, first.get(HeaderKeys.MESSAGE_ID))
                    .put(HeaderKeys.SENT_AT, "2026-11-23T15:30:00"));
            Packet second = in.read();
            out.write(Packet.replyTo(second, PacketType.SEND_FAIL).put(HeaderKeys.REASON, "없는 사용자입니다: x"));
            in.read();
        })) {
            Connection c = Connection.open(server.address(), new NoopListener());
            ServerApi api = new ServerApi(c);
            String id = UUID.randomUUID().toString();
            ServerApi.SendResult ok = api.send(new MessageData(id, List.of("lee", "park"), List.of(), "안녕<br>\n"));
            Packet req = seen.get(0);
            check.accept("SEND 헤더: reqId·messageId·receivers, 본문 HTML",
                    req.getType() == PacketType.SEND && req.getReqId() > 0 && id.equals(req.get(HeaderKeys.MESSAGE_ID))
                            && "lee,park".equals(req.get("receivers")) && !req.has("cc")
                            && "안녕<br>\n".equals(req.getText()));
            check.accept("SEND_OK → 서버가 정한 보낸 시각",
                    ok.isOk() && ok.getSentAt().equals(LocalDateTime.of(2026, 11, 23, 15, 30, 0)));
            ServerApi.SendResult fail = api.send(new MessageData(UUID.randomUUID().toString(), List.of("x"), List.of(), "a"));
            check.accept("SEND_FAIL → 이유", !fail.isOk() && "없는 사용자입니다: x".equals(fail.getFailReason()));
            c.close();
        }
    }

    private static void receiveAndAck() throws Exception {
        Path base = Files.createTempDirectory("coolmsg-client-home");
        String id = UUID.randomUUID().toString();
        List<String> acks = Collections.synchronizedList(new ArrayList<>());
        Packet msg = new Packet(PacketType.MSG).put(HeaderKeys.MESSAGE_ID, id).put("sender", "kim")
                .put("receivers", "lee").put("sentAt", "2026-11-23T15:30:00").setText("밀린 메시지");
        Path inboxFile = base.resolve("lee/inbox/" + id + ".txt");
        boolean[] savedBeforeAck = new boolean[1];
        try (FakeServer server = new FakeServer((in, out) -> {
            Packet login = in.read();
            out.write(Packet.replyTo(login, PacketType.LOGIN_OK).setText("kim|김철수|true\nlee|이영희|true"));
            out.write(msg); // LOGIN_OK 바로 뒤의 밀린 메시지
            Packet ack1 = in.read();
            savedBeforeAck[0] = Files.exists(inboxFile); // ACK가 왔을 때 이미 저장돼 있어야 한다
            acks.add(ack1.getTypeName() + ":" + ack1.get(HeaderKeys.MESSAGE_ID));
            out.write(msg); // ACK 유실 뒤 재전달 흉내
            Packet ack2 = in.read();
            acks.add(ack2.getTypeName() + ":" + ack2.get(HeaderKeys.MESSAGE_ID));
            in.read(); // 클라이언트가 닫을 때까지
        })) {
            TestController controller = new TestController(new ClientPaths(base));
            ClientController.LoginOutcome outcome = controller.login(server.address(), "lee", "pass".toCharArray());
            check.accept("로그인 성공", outcome.ok);
            waitUntil(() -> acks.size() == 2);
            check.accept("LOGIN_OK 바로 뒤의 MSG도 받아 저장 → ACK", acks.size() >= 1
                    && acks.get(0).equals("ACK:" + id));
            check.accept("순서: 보관함에 저장한 뒤에 ACK (4.1)", savedBeforeAck[0]);
            check.accept("같은 메시지가 다시 오면 저장하지 않고 ACK만",
                    acks.size() == 2 && acks.get(1).equals("ACK:" + id) && controller.mailbox().inbox().size() == 1);
            check.accept("보관함은 ~/.coolmsg/{아이디}/inbox", Files.exists(inboxFile));
            check.accept("이름 표시: 받은 사용자 목록으로", "김철수".equals(controller.nameOf("kim"))
                    && "없는id".equals(controller.nameOf("없는id"))
                    && "이영희 외 1명".equals(controller.describeReceivers(List.of("lee", "kim"))));
        }
    }

    private static void draftCheck() {
        check.accept("받는 사람 없음", ClientController.checkDraft(List.of(), "안녕") != null);
        check.accept("공백뿐인 본문", ClientController.checkDraft(List.of("lee"), "  \n ") != null);
        check.accept("1MB 초과 (HTML로 바꾼 크기 기준)",
                ClientController.checkDraft(List.of("lee"), "<".repeat(300_000)) != null);
        check.accept("정상", ClientController.checkDraft(List.of("lee"), "안녕") == null);
    }

    private static void receiptsAndReads() throws Exception {
        Path dir = Files.createTempDirectory("coolmsg-receipts");
        Mailbox box = new Mailbox(dir);
        MessageData sent = new MessageData(UUID.randomUUID().toString(), List.of("lee", "park"), List.of("choi"),
                "보냄");
        sent.setSender("kim");
        sent.setSentAt(TimeFormat.now());
        box.saveSent(sent);
        String id = sent.getMessageId();
        check.accept("처음에는 모두 전송됨, 요약 0/3", box.sent().get(0).readSummary().equals("0/3")
                && box.sent().get(0).stateOf("lee") == coolmsg.common.model.DeliveryState.SENT);
        check.accept("RECEIPT READ → 반영", box.applyReceipt(id, "lee", coolmsg.common.model.DeliveryState.READ,
                "2026-11-23T15:42:07"));
        check.accept("늦게 온 DELIVERED·같은 READ → 무시 (앞으로만)",
                !box.applyReceipt(id, "lee", coolmsg.common.model.DeliveryState.DELIVERED, "2026-11-23T15:40:00")
                        && !box.applyReceipt(id, "lee", coolmsg.common.model.DeliveryState.READ, "2026-11-23T16:00:00"));
        MailItem item = box.sent().get(0);
        check.accept("읽은 시각·요약 1/3", item.readAtText("lee").equals("2026-11-23 15:42")
                && item.readSummary().equals("1/3"));
        check.accept("보낸 내역에 없는 메시지의 RECEIPT는 무시",
                !box.applyReceipt(UUID.randomUUID().toString(), "lee", coolmsg.common.model.DeliveryState.READ, "x"));
        java.util.Map<String, String> status = new java.util.LinkedHashMap<>();
        status.put("messageId", id);
        status.put("state.lee", "READ");
        status.put("readAt.lee", "2026-11-23T15:42:07");
        status.put("state.park", "DELETED");
        status.put("state.choi", "DELIVERED");
        check.accept("STATUS_OK 반영 → 바뀜", box.applyStatus(id, status));
        item = box.sent().get(0);
        check.accept("삭제된 사용자는 분모에서 뺌 (U4): 1/2, choi 안 읽음",
                item.isDeletedUser("park") && item.readSummary().equals("1/2")
                        && item.stateOf("choi") == coolmsg.common.model.DeliveryState.DELIVERED);
        check.accept("같은 STATUS 다시 → 바뀌지 않음", !box.applyStatus(id, status));

        // 받은 메시지: 읽었지만 서버에 못 알림 → 목록에 남음 (U49)
        MessageData in = received("lee", LocalDateTime.of(2026, 11, 23, 9, 0, 0), "받음");
        box.saveReceived(in);
        check.accept("안 읽은 메시지는 다시 보낼 READ 아님", box.unreportedReads().isEmpty());
        box.markRead(in.getMessageId());
        check.accept("읽었는데 아직 못 알림 → 다시 보낼 목록에", box.unreportedReads().equals(List.of(in.getMessageId())));
        box.markReadReported(in.getMessageId());
        check.accept("알렸다고 기록 → 목록에서 빠짐", box.unreportedReads().isEmpty());

        // 로그인 때 못 알린 READ를 다시 보냄 (가짜 서버)
        Path base = Files.createTempDirectory("coolmsg-reread");
        Mailbox leeBox = new Mailbox(base.resolve("lee"));
        MessageData pending = received("kim", LocalDateTime.of(2026, 11, 23, 10, 0, 0), "못 알린 읽음");
        leeBox.saveReceived(pending);
        leeBox.markRead(pending.getMessageId());
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        try (FakeServer server = new FakeServer((in2, out) -> {
            Packet login = in2.read();
            out.write(Packet.replyTo(login, PacketType.LOGIN_OK).setText("kim|김철수|true\nlee|이영희|true"));
            Packet next = in2.read();
            seen.add(next.getTypeName() + ":" + next.get(HeaderKeys.MESSAGE_ID));
            in2.read();
        })) {
            TestController controller = new TestController(new ClientPaths(base));
            controller.login(server.address(), "lee", "pass".toCharArray());
            waitUntil(() -> !seen.isEmpty());
            check.accept("로그인 성공 직후 못 알린 READ를 다시 보냄 (U49)",
                    seen.equals(List.of("READ:" + pending.getMessageId())));
            check.accept("다시 보낸 뒤에는 알렸다고 기록", new Mailbox(base.resolve("lee")).unreportedReads().isEmpty());
        }
    }

    private static void orgCheckTree() throws Exception {
        List<coolmsg.client.model.UserEntry> users = List.of(
                new coolmsg.client.model.UserEntry("kim", "김철수", true),
                new coolmsg.client.model.UserEntry("lee", "이영희", false),
                new coolmsg.client.model.UserEntry("park", "박민수", false),
                new coolmsg.client.model.UserEntry("choi", "최지우", true));
        coolmsg.common.model.OrgTree org = coolmsg.common.model.OrgTree.parse(List.of(
                "D|학교", "D|학교>교무실", "U|학교>교무실|kim", "D|학교>교무실>1학년", "U|학교>교무실>1학년|lee",
                "D|학교>행정실", "U|학교>행정실|park", "U|학교>행정실|newbie"), new ArrayList<>());
        coolmsg.client.ui.OrgCheckTree tree = new coolmsg.client.ui.OrgCheckTree();
        tree.showOrg(org, users);
        check.accept("조직도에 없는 사람(choi)은 '미배치'에, 목록에 없는 아이디(newbie)는 건너뜀 (U50)",
                "NONE".equals(tree.deptState("미배치")) && !tree.toggle("newbie"));
        tree.toggle("교무실");
        check.accept("부서 체크 → 하위 부서 포함 모두 (나도 포함)", tree.checkedIds().equals(List.of("kim", "lee"))
                && "ALL".equals(tree.deptState("1학년")));
        tree.toggle("lee");
        check.accept("한 명 풀면 상위 부서는 '일부', 하위 부서는 '없음'",
                "SOME".equals(tree.deptState("교무실")) && "NONE".equals(tree.deptState("1학년"))
                        && "SOME".equals(tree.deptState("학교")));
        tree.toggle("교무실");
        check.accept("'일부'인 부서를 누르면 모두 체크", "ALL".equals(tree.deptState("교무실")));
        tree.toggle("교무실");
        check.accept("'모두'인 부서를 누르면 모두 풀림", tree.checkedIds().isEmpty());
        tree.toggle("학교");
        tree.toggle("미배치");
        check.accept("트리 순서, 중복 없이", tree.checkedIds().equals(List.of("kim", "lee", "park", "choi")));
        tree.clearChecks();
        check.accept("[메시지 쓰기] 뒤 체크 모두 풀기", tree.checkedIds().isEmpty());
        coolmsg.client.ui.OrgCheckTree flat = new coolmsg.client.ui.OrgCheckTree();
        flat.showFlat(users);
        flat.toggle("park");
        check.accept("임시 표시도 체크박스 목록", flat.checkedIds().equals(List.of("park")) && flat.deptState("미배치") == null);

        // 작성 창 [받는 사람 선택]: 지금 받는 사람을 체크한 채로 연다 (U57)
        coolmsg.client.ui.OrgCheckTree picker = new coolmsg.client.ui.OrgCheckTree();
        picker.showOrg(org, users);
        picker.setChecked(java.util.Set.of("lee", "choi"));
        check.accept("[받는 사람 선택]: 이미 받는 사람은 체크된 채로, 그 부서는 모두/일부로 (U57)",
                picker.checkedIds().equals(List.of("lee", "choi")) && "ALL".equals(picker.deptState("1학년"))
                        && "SOME".equals(picker.deptState("교무실")) && "ALL".equals(picker.deptState("미배치"))
                        && "NONE".equals(picker.deptState("행정실")));
        picker.toggle("행정실");
        picker.setChecked(java.util.Set.of("kim"));
        check.accept("다시 열 때는 그때의 받는 사람만 체크 (이전 체크는 풀림)", picker.checkedIds().equals(List.of("kim")));

        // ORG 요청 (가짜 서버)
        try (FakeServer server = new FakeServer((in, out) -> {
            Packet r1 = in.read();
            out.write(Packet.replyTo(r1, PacketType.ORG_OK).setText("D|학교\nU|학교|kim\n"));
            Packet r2 = in.read();
            out.write(Packet.replyTo(r2, PacketType.ORG_NONE).put(HeaderKeys.REASON, "조직도 설정이 없습니다."));
            Packet r3 = in.read();
            out.write(Packet.replyTo(r3, PacketType.ORG_OK).setText("U|학교|kim\n"));
            in.read();
        })) {
            Connection c = Connection.open(server.address(), new NoopListener());
            ServerApi api = new ServerApi(c);
            coolmsg.common.model.OrgTree got = api.org();
            check.accept("ORG_OK → 조직도", got != null && "학교".equals(got.getRoot().getName()));
            check.accept("ORG_NONE → null (임시 표시)", api.org() == null);
            check.accept("형식 오류(맨 위 부서 없음) → null (임시 표시)", api.org() == null);
            c.close();
        }
    }

    /** 폭이 좁아지면 줄이 늘어나 높이가 커지고, 모든 단추가 폭 안에 들어가는지. */
    private static void wrapLayout() {
        javax.swing.JPanel panel = new javax.swing.JPanel(new coolmsg.client.ui.WrapLayout(java.awt.FlowLayout.LEFT, 4, 2));
        for (int i = 0; i < 10; i++) {
            panel.add(new javax.swing.JButton("사용자" + i + " ×"));
        }
        javax.swing.JViewport viewport = new javax.swing.JViewport();
        viewport.setView(panel);
        viewport.setSize(800, 100);
        int wide = panel.getPreferredSize().height;
        viewport.setSize(200, 100);
        int narrow = panel.getPreferredSize().height;
        check.accept("좁아지면 여러 줄로 → 필요한 높이가 커짐 (예전 FlowLayout은 항상 한 줄 높이)", narrow > wide * 2);
        panel.setSize(200, narrow);
        panel.doLayout();
        boolean inside = true;
        for (java.awt.Component c : panel.getComponents()) {
            java.awt.Rectangle r = c.getBounds();
            inside &= r.x + r.width <= 200 && r.y + r.height <= narrow;
        }
        check.accept("줄을 바꾼 뒤 모든 단추가 칸 안에 보임 (잘리지 않음)", inside);
    }

    // ===================================================================

    /** 끊김 때 로그인 창을 띄우지 않는 컨트롤러 (테스트에서 창이 뜨지 않게). */
    private static final class TestController extends ClientController {
        TestController(ClientPaths paths) {
            super(paths);
        }

        @Override
        public void onDisconnected(String reason, boolean byServer) {
            // 테스트에서는 아무것도 하지 않는다
        }
    }

    private static final class NoopListener implements Connection.Listener {
        @Override
        public void onPacket(Packet packet) {
        }

        @Override
        public void onDisconnected(String reason, boolean byServer) {
        }
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void waitUntil(Condition c) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (!c.holds() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private interface Script {
        void play(PacketReader in, PacketWriter out) throws Exception;
    }

    /** 접속 하나를 받아 각본대로 주고받는 가짜 서버. */
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
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
