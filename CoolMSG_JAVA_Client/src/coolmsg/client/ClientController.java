package coolmsg.client;

import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import coolmsg.client.model.MailItem;
import coolmsg.client.model.UserEntry;
import coolmsg.client.net.Connection;
import coolmsg.client.net.ConnectionTester;
import coolmsg.client.net.Heartbeat;
import coolmsg.client.net.ServerAddress;
import coolmsg.client.net.ServerApi;
import coolmsg.client.store.ClientPaths;
import coolmsg.client.store.ClientSettings;
import coolmsg.client.store.Mailbox;
import coolmsg.client.ui.ComposeFrame;
import coolmsg.client.ui.LoginFrame;
import coolmsg.client.ui.MainFrame;
import coolmsg.client.ui.MessageManagerFrame;
import coolmsg.client.ui.Toast;
import coolmsg.common.FormatException;
import coolmsg.common.ProtocolInfo;
import coolmsg.common.model.DeliveryState;
import coolmsg.common.model.HtmlText;
import coolmsg.common.model.MessageData;
import coolmsg.common.model.OrgTree;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;

/**
 * 화면·통신·보관함 사이의 중재자이자 프로그램 생명주기의 단일 지점 (설계서 4.1, 5장 U33).
 *
 * - 화면(ui)은 통신(net)을 직접 만지지 않고 이 클래스의 메서드만 부른다.
 * - 통신이 알려 오는 일(연결 끊김, 새 메시지)은 Connection.Listener로 받아 Swing 스레드에서 화면을 바꾼다(C9).
 * - 새 메시지는 반드시 "보관함 저장 → ACK → 화면 갱신" 순서 (4.1). 저장에 실패하면 ACK를 보내지 않는다
 *   (서버에 미전달로 남아 다음 로그인 때 다시 받는다).
 * - 종료 경로를 두 메서드로 모은다: requestCloseMainWindow()(메인 창 X), shutdown()(프로그램 종료).
 *   나중에 시스템 트레이를 넣을 때는 requestCloseMainWindow()가 "창 숨기기"로 바뀌고,
 *   트레이 [종료]가 shutdown()을 부르면 된다.
 */
public class ClientController implements Connection.Listener {

    /** 새 메시지 알림을 묶어 보내는 시간 (U41): 처음 메시지가 온 뒤 1초 동안 온 것을 한 번에 알린다. */
    public static final int NOTIFY_BATCH_MILLIS = 1000;

    private final ClientPaths paths;
    private ClientSettings settings;
    private LoginFrame loginFrame;
    private MainFrame mainFrame;
    private MessageManagerFrame managerFrame;
    /** 열려 있는 작성·보기 창. 로그아웃·끊김 때 함께 닫는다. */
    private final List<Window> childWindows = new ArrayList<>();

    // 로그인 중일 때만 있는 것들 (Swing 스레드와 통신 스레드가 함께 보므로 volatile)
    private volatile Connection connection;
    private volatile ServerApi api;
    private volatile Heartbeat heartbeat;
    private volatile Mailbox mailbox;
    private volatile String myId;
    private volatile List<UserEntry> users = List.of();
    /** 로그인 뒤 받은 조직도. 못 받았으면 null(임시 표시). 작성 창의 [받는 사람 선택]도 이것을 쓴다(U57). */
    private volatile OrgTree orgTree;

    // 새 메시지 알림 묶기 (Swing 스레드에서만)
    private final List<MessageData> unnotified = new ArrayList<>();
    private final Timer notifyTimer;
    // 읽음 알림 묶기 (U51, Swing 스레드에서만): [messageId, 읽은 사람]
    private final List<String[]> unnotifiedReads = new ArrayList<>();
    private final Timer readNotifyTimer;

    public ClientController(ClientPaths paths) {
        this.paths = paths;
        this.notifyTimer = new Timer(NOTIFY_BATCH_MILLIS, e -> flushNotifications());
        this.notifyTimer.setRepeats(false);
        this.readNotifyTimer = new Timer(NOTIFY_BATCH_MILLIS, e -> flushReadNotifications());
        this.readNotifyTimer.setRepeats(false);
    }

    /** 프로그램 시작: 저장된 서버 주소·아이디를 채운 로그인 화면을 띄운다. Swing 스레드에서 부른다. */
    public void start() {
        settings = ClientSettings.load(paths.settingsFile());
        showLoginFrame(null);
    }

    // =====================================================================
    // 로그인 (LoginFrame이 SwingWorker 안에서 부른다 — Swing 스레드 아님)
    // =====================================================================

    /** 로그인 결과: 성공이면 사용자 목록, 실패면 이유. */
    public static final class LoginOutcome {
        public final boolean ok;
        public final String message;
        public final String userId;
        public final List<UserEntry> users;

        LoginOutcome(boolean ok, String message, String userId, List<UserEntry> users) {
            this.ok = ok;
            this.message = message;
            this.userId = userId;
            this.users = users;
        }
    }

    /**
     * 서버에 연결하고 로그인한다. 오래 걸릴 수 있으므로 Swing 스레드가 아닌 곳에서 부른다.
     * 성공하면 연결을 유지하고 하트비트를 시작한다. 실패하면 연결을 닫는다.
     */
    public LoginOutcome login(ServerAddress address, String userId, char[] password) {
        // 보관함은 LOGIN 전에 준비한다: LOGIN_OK 바로 뒤에 밀린 MSG가 오므로,
        // 받기 스레드가 MSG를 저장하려 할 때 이미 있어야 한다
        mailbox = new Mailbox(paths.mailboxDir(userId));
        myId = userId;
        Connection c;
        try {
            c = Connection.open(address, this);
        } catch (IOException e) {
            mailbox = null;
            return new LoginOutcome(false, ConnectionTester.describe(e), userId, List.of());
        }
        ServerApi a = new ServerApi(c);
        api = a; // 밀린 MSG의 ACK를 보내려면 LOGIN_OK 전에 있어야 한다
        try {
            ServerApi.LoginResult r = a.login(userId, password);
            if (!r.isOk()) {
                api = null;
                mailbox = null;
                c.close();
                return new LoginOutcome(false, r.getFailReason(), userId, List.of());
            }
            connection = c;
            users = r.getUsers();
            heartbeat = new Heartbeat(a);
            for (String id : mailbox.unreportedReads()) { // 끊겨서 못 알린 READ를 다시 보낸다 (U49)
                reportRead(id);
            }
            return new LoginOutcome(true, null, userId, r.getUsers());
        } catch (IOException e) {
            api = null;
            mailbox = null;
            c.close();
            return new LoginOutcome(false, e.getMessage(), userId, List.of());
        }
    }

    /** 로그인 성공 뒤 화면 전환. Swing 스레드에서 부른다. 로그인 정보는 성공했을 때만 저장한다(U34). */
    public void onLoginSucceeded(String serverText, LoginOutcome outcome) {
        settings.setServer(serverText.strip());
        settings.setUserId(outcome.userId);
        try {
            settings.save(paths.settingsFile());
        } catch (IOException e) {
            System.err.println("로그인 정보를 저장하지 못했습니다: " + e); // 로그인 자체는 계속한다
        }
        if (loginFrame != null) {
            loginFrame.dispose();
            loginFrame = null;
        }
        mainFrame = new MainFrame(this, outcome.userId, outcome.users);
        mainFrame.setUnreadCount(mailbox.unreadCount());
        mainFrame.setVisible(true);
        loadOrgChart();
        if (!unnotified.isEmpty()) {
            notifyTimer.restart(); // 메인 창보다 먼저 도착한 밀린 메시지 알림
        }
    }

    /** 조직도를 받아 메인 창을 부서 트리로 바꾼다. 응답은 다른 스레드에서 최대 3초 기다린다 (2.10). */
    private void loadOrgChart() {
        ServerApi a = api;
        if (a == null) {
            return;
        }
        Thread t = new Thread(() -> {
            OrgTree org = a.org();
            SwingUtilities.invokeLater(() -> {
                if (mainFrame == null || api != a) {
                    return; // 그사이 로그아웃·끊김
                }
                orgTree = org;
                if (org != null) {
                    mainFrame.showOrg(org, users); // 지금까지 반영된 접속 상태로
                } else {
                    mainFrame.showTemporary(users);
                }
            });
        }, "org");
        t.setDaemon(true);
        t.start();
    }

    // =====================================================================
    // 로그아웃·종료 — 종료 경로는 여기 두 곳뿐 (U33)
    // =====================================================================

    /** 메뉴 [로그아웃]: 로그아웃하고 로그인 화면으로. Swing 스레드에서 부른다. */
    public void logout() {
        disconnect(true);
        closeLoggedInWindows();
        showLoginFrame(null);
    }

    /**
     * 메인 창 X 버튼. 지금은 확인 없이 로그아웃 후 종료한다(U33).
     * 시스템 트레이를 넣을 때는 이 메서드만 "창 숨기기"로 바꾼다.
     */
    public void requestCloseMainWindow() {
        shutdown();
    }

    /** 프로그램 종료: 로그인 중이면 로그아웃을 알리고 연결을 닫은 뒤 끝낸다. */
    public void shutdown() {
        Connection c = connection;
        disconnect(true);
        if (c != null) {
            c.awaitClosed(1000); // LOGOUT이 실제로 전달될 시간 (최대 1초)
        }
        closeLoggedInWindows();
        if (loginFrame != null) {
            loginFrame.dispose();
        }
        System.exit(0);
    }

    // =====================================================================
    // Connection.Listener — 받기 스레드에서 불린다
    // =====================================================================

    @Override
    public void onDisconnected(String reason, boolean byServer) {
        SwingUtilities.invokeLater(() -> {
            disconnect(false);
            closeLoggedInWindows();
            showLoginFrame(reason); // U9, U30: 이유를 보여 주고 로그인 화면으로
        });
    }

    @Override
    public void onPacket(Packet packet) {
        switch (packet.getType()) {
            case MSG:
                onMessage(packet);
                break;
            case PRESENCE:
                onPresence(packet);
                break;
            case RECEIPT:
                onReceipt(packet);
                break;
            default:
                break; // 모르는 알림은 무시
        }
    }

    /** 접속 상태 변경: 목록의 ●/○만 바꾼다 (2.5). 목록에 없는 사람(로그인 뒤 추가된 사용자)은 다음 로그인 때 (U50). */
    private void onPresence(Packet packet) {
        String userId = packet.get(HeaderKeys.USER_ID);
        boolean online = "true".equals(packet.get(HeaderKeys.ONLINE));
        if (userId == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            List<UserEntry> updated = new ArrayList<>();
            for (UserEntry u : users) {
                updated.add(u.getId().equals(userId) ? new UserEntry(u.getId(), u.getName(), online) : u);
            }
            users = List.copyOf(updated);
            if (mainFrame != null) {
                mainFrame.setOnline(userId, online);
            }
        });
    }

    /** 내가 보낸 메시지의 수신·읽음 알림: 보낸 내역에 반영(앞으로만), 접속 중에 온 READ면 알림 창 (U51). */
    private void onReceipt(Packet packet) {
        Mailbox box = mailbox;
        if (box == null) {
            return;
        }
        String messageId = packet.get(HeaderKeys.MESSAGE_ID);
        String userId = packet.get(HeaderKeys.USER_ID);
        DeliveryState state = DeliveryState.fromName(packet.get(HeaderKeys.STATE));
        boolean changed;
        try {
            changed = box.applyReceipt(messageId, userId, state, packet.get(HeaderKeys.AT));
        } catch (IOException e) {
            System.err.println("수신·읽음 알림을 저장하지 못했습니다: " + e.getMessage());
            return;
        }
        if (!changed) {
            return; // 보낸 내역에 없거나(지움) 이미 반영됨
        }
        boolean notify = state == DeliveryState.READ && !"true".equals(packet.get(HeaderKeys.QUEUED));
        SwingUtilities.invokeLater(() -> {
            refreshViews();
            if (notify) {
                unnotifiedReads.add(new String[] {messageId, userId});
                if (!readNotifyTimer.isRunning()) {
                    readNotifyTimer.start();
                }
            }
        });
    }

    /** 읽음 알림 창: 1건이면 "이영희님이 메시지를 읽었습니다", 여러 건이면 "n건의 읽음 알림" (U51). */
    private void flushReadNotifications() {
        if (unnotifiedReads.isEmpty() || mailbox == null || mainFrame == null) {
            unnotifiedReads.clear();
            return;
        }
        List<String[]> batch = new ArrayList<>(unnotifiedReads);
        unnotifiedReads.clear();
        if (batch.size() == 1) {
            String messageId = batch.get(0)[0];
            String preview = "";
            for (MailItem m : mailbox.sent()) {
                if (m.getMessageId().equals(messageId)) {
                    preview = HtmlText.preview(m.getData().getBody(), 30);
                }
            }
            Toast.show(Toast.Kind.READ_RECEIPT, nameOf(batch.get(0)[1]) + "님이 메시지를 읽었습니다", preview,
                    () -> openSent(messageId));
        } else {
            Toast.show(Toast.Kind.READ_RECEIPT, batch.size() + "건의 읽음 알림", null, () -> openManager(false));
        }
    }

    /** 읽음 알림 창을 눌렀을 때(1건): 보낸 메시지 탭에서 그 줄을 고른다. */
    private void openSent(String messageId) {
        openManager(false);
        if (managerFrame != null) {
            managerFrame.select(messageId, false);
        }
    }

    /** 서버에 READ를 알리고, 대기열에 넣었으면 알렸다고 기록한다 (U49). */
    private void reportRead(String messageId) {
        ServerApi a = api;
        Mailbox box = mailbox;
        if (a == null || box == null || !a.read(messageId)) {
            return; // 다음 로그인 때 다시
        }
        try {
            box.markReadReported(messageId);
        } catch (IOException e) {
            System.err.println("읽음 알림 기록을 저장하지 못했습니다(다음 로그인 때 다시 보냄): " + e.getMessage());
        }
    }

    /**
     * 보낸 메시지의 받는 사람별 상태를 서버에 묻고 보낸 내역에 반영한다 (STATUS, 2.6).
     * 관리함에서 보낸 메시지를 고를 때와 [새로고침] 때 부른다. 응답은 다른 스레드에서 기다린다.
     */
    public void requestStatus(String messageId) {
        ServerApi a = api;
        Mailbox box = mailbox;
        if (a == null || box == null) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Packet reply = a.status(messageId);
                if (reply.getType() == PacketType.STATUS_OK && box.applyStatus(messageId, reply.getHeader())) {
                    SwingUtilities.invokeLater(this::refreshViews);
                }
            } catch (IOException e) {
                System.err.println("상태 조회 실패: " + e.getMessage()); // 마지막으로 저장한 상태를 그대로 보여 준다
            }
        }, "status");
        t.setDaemon(true);
        t.start();
    }

    /** 새 메시지: 저장 → ACK → 화면 (4.1). 받기 스레드에서 불린다. */
    void onMessage(Packet packet) {
        Mailbox box = mailbox;
        ServerApi a = api;
        if (box == null || a == null) {
            return; // 로그아웃 중. ACK하지 않으면 다음 로그인 때 다시 온다
        }
        MessageData data;
        try {
            data = MessageData.fromHeader(packet.getHeader(), packet.getText());
        } catch (FormatException e) {
            System.err.println("잘못된 MSG를 버립니다: " + e.getMessage());
            return;
        }
        Mailbox.ReceiveResult result;
        try {
            result = box.saveReceived(data);
        } catch (IOException e) {
            System.err.println("받은 메시지를 저장하지 못했습니다 (ACK 보류): " + e.getMessage());
            return;
        }
        a.ack(data.getMessageId()); // 중복·지운 메시지여도 ACK (U38)
        if (result == Mailbox.ReceiveResult.SAVED) {
            SwingUtilities.invokeLater(() -> onNewMessage(data));
        }
    }

    /** Swing 스레드: 목록·개수를 바로 갱신하고, 알림 창은 1초 묶어서 (U41). */
    private void onNewMessage(MessageData data) {
        if (mailbox == null) {
            return;
        }
        refreshViews();
        unnotified.add(data);
        if (!notifyTimer.isRunning()) {
            notifyTimer.start(); // 처음 온 메시지에서 1초 뒤 한 번 알림
        }
    }

    private void flushNotifications() {
        if (unnotified.isEmpty() || mailbox == null) {
            unnotified.clear();
            return;
        }
        if (mainFrame == null) {
            return; // 메인 창이 뜬 뒤 onLoginSucceeded가 다시 부른다
        }
        List<MessageData> batch = new ArrayList<>(unnotified);
        unnotified.clear();
        if (batch.size() == 1) {
            MessageData d = batch.get(0);
            Toast.show(Toast.Kind.NEW_MESSAGE, "새 메시지: " + nameOf(d.getSender()), HtmlText.preview(d.getBody(), 30),
                    () -> openReceived(d.getMessageId()));
        } else {
            Toast.show(Toast.Kind.NEW_MESSAGE, batch.size() + "개의 새 메시지", null, () -> openManager(true));
        }
    }

    /** 알림 창을 눌렀을 때(1개): 받은 메시지 탭을 열고 그 메시지 줄을 고른다 (U48). */
    private void openReceived(String messageId) {
        openManager(true);
        if (managerFrame != null) {
            managerFrame.select(messageId, true);
        }
    }

    // =====================================================================
    // 메시지 보내기 (ComposeFrame이 SwingWorker 안에서 부른다)
    // =====================================================================

    /** 보내기 전 검사 (U5). 본문은 HTML. 문제가 있으면 안내 문장, 없으면 null. 서버도 같은 검사를 다시 한다. */
    public static String checkDraft(List<String> receivers, List<String> cc, String html) {
        if (receivers.isEmpty()) {
            return cc.isEmpty() ? "받는 사람을 한 명 이상 선택해 주세요."
                    : "참조만으로는 보낼 수 없습니다. 받는 사람을 한 명 이상 선택해 주세요.";
        }
        if (receivers.size() + cc.size() > ProtocolInfo.MAX_RECIPIENTS) {
            return "받는 사람과 참조는 합쳐서 " + ProtocolInfo.MAX_RECIPIENTS + "명까지 보낼 수 있습니다.";
        }
        if (HtmlText.isBlank(html)) {
            return "본문을 입력해 주세요.";
        }
        if (html.getBytes(StandardCharsets.UTF_8).length > ProtocolInfo.MAX_BODY_BYTES) {
            return "본문이 너무 깁니다 (1MB 이하). 인용한 원본의 필요 없는 부분을 지워 보세요.";
        }
        return null;
    }

    // =====================================================================
    // 회신·전체회신 (설계서 2.11, 5장 U61)
    // =====================================================================

    /**
     * 회신할 받는 사람과 참조 (2.11 표). [0] 받는 사람, [1] 참조.
     * 회신: 원본을 보낸 사람. 전체회신: 보낸 사람 + 원본 받는 사람(나 제외), 참조는 원본 참조(나 제외).
     * 받는 사람이 비면(내가 나에게 보낸 메시지) 보낸 사람(나)으로.
     */
    public static List<List<String>> replyRecipients(MessageData original, String myId, boolean all) {
        java.util.Set<String> to = new java.util.LinkedHashSet<>();
        java.util.Set<String> cc = new java.util.LinkedHashSet<>();
        to.add(original.getSender());
        if (all) {
            to.addAll(original.getReceivers());
            to.remove(myId);
            if (!original.getSender().equals(myId)) {
                to.add(original.getSender()); // 보낸 사람은 남긴다 (나 자신이 아니면)
            }
            cc.addAll(original.getCc());
            cc.remove(myId);
            cc.removeAll(to); // 한 사람은 한 칸에만 (U60)
        }
        if (to.isEmpty()) {
            to.add(original.getSender());
        }
        return List.of(new ArrayList<>(to), new ArrayList<>(cc));
    }

    /** 회신 본문: 맨 위 빈 입력 공간 + 원본 보낸 사람·시각 + 원본 본문(정화) (4.3, U61). 가로줄은 넣지 않는다. */
    public String quoteHtml(MessageData original) {
        String when = original.getSentAt() == null ? "" : MailItem.LIST_TIME.format(original.getSentAt());
        return "<p></p><p></p>"
                + "<p><font color=\"#666666\">보낸 사람: " + HtmlText.fromPlain(nameWithId(original.getSender()))
                + "<br>보낸 시각: " + when + "</font></p>"
                + HtmlText.sanitize(original.getBody());
    }

    /** 관리함 [회신]·[전체회신] (Swing 스레드). */
    public void reply(MailItem item, boolean all) {
        MessageData original = item.getData();
        List<List<String>> who = replyRecipients(original, myId, all);
        ComposeFrame f = new ComposeFrame(this, new ComposeFrame.Draft(who.get(0), who.get(1), quoteHtml(original),
                original.getMessageId(), all ? "전체회신" : "회신"));
        track(f);
        f.setVisible(true);
    }

    /**
     * 메시지를 보낸다 (응답을 기다림, Swing 스레드 아님). 성공하면 보낸 내역에 저장한다.
     *
     * @param messageId 작성 창이 정한 ID. 다시 보낼 때도 같은 값 (U10, U35)
     * @return 성공이면 null, 실패면 보여 줄 이유
     */
    public String sendMessage(String messageId, List<String> receivers, List<String> cc, String html, String replyTo) {
        ServerApi a = api;
        Mailbox box = mailbox;
        if (a == null || box == null) {
            return "서버에 연결되어 있지 않습니다.";
        }
        MessageData draft = new MessageData(messageId, receivers, cc, HtmlText.sanitize(html));
        draft.setReplyTo(replyTo); // 기록용 (2.11)
        ServerApi.SendResult r;
        try {
            r = a.send(draft);
        } catch (IOException e) {
            return e.getMessage();
        }
        if (!r.isOk()) {
            return r.getFailReason();
        }
        draft.setSender(myId);
        draft.setSentAt(r.getSentAt());
        try {
            box.saveSent(draft);
        } catch (IOException e) {
            // 서버에는 저장됐으므로 보내기 자체는 성공. 내 보낸 내역에만 없다
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(mainFrame,
                    "메시지는 보냈지만 보낸 내역에 저장하지 못했습니다.\n원인: " + e.getMessage(),
                    "보낸 내역 저장 실패", JOptionPane.WARNING_MESSAGE));
        }
        SwingUtilities.invokeLater(this::refreshViews);
        return null;
    }

    // =====================================================================
    // 화면에서 부르는 것들 (Swing 스레드)
    // =====================================================================

    public void openCompose(List<String> receivers) {
        ComposeFrame f = new ComposeFrame(this, receivers);
        track(f);
        f.setVisible(true);
    }

    /** 메시지 관리함 창을 연다(이미 있으면 앞으로). inbox면 받은 메시지 탭. */
    public void openManager(boolean inbox) {
        if (mailbox == null) {
            return;
        }
        if (managerFrame == null || !managerFrame.isDisplayable()) {
            managerFrame = new MessageManagerFrame(this);
        } else {
            managerFrame.refresh();
        }
        managerFrame.showTab(inbox);
        managerFrame.setVisible(true);
        managerFrame.toFront();
    }

    /** 받은 메시지를 읽음으로 표시한다 (관리함에서 줄을 골랐을 때, U48). 처음 읽었을 때 한 번만 READ를 보낸다. */
    public void markRead(String messageId) {
        Mailbox box = mailbox;
        if (box == null) {
            return;
        }
        try {
            if (box.markRead(messageId)) {
                reportRead(messageId); // 처음 읽었을 때 한 번만 READ (2.6)
            }
        } catch (IOException e) {
            System.err.println("읽음 표시를 저장하지 못했습니다: " + e.getMessage());
        }
        refreshViews();
    }

    /** 내 PC 보관함에서 지운다. 받은 메시지는 다시 받아도 되살아나지 않게 ID를 남긴다(U38). */
    public void deleteMessage(String messageId, boolean inbox) {
        try {
            if (inbox) {
                mailbox.deleteReceived(messageId);
            } else {
                mailbox.deleteSent(messageId);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(managerFrame, "지우지 못했습니다.\n원인: " + e.getMessage(),
                    "삭제 실패", JOptionPane.ERROR_MESSAGE);
        }
        refreshViews();
    }

    public Mailbox mailbox() {
        return mailbox;
    }

    /** 로그인 뒤 받은 조직도 (복사본). 못 받았으면 null. */
    public OrgTree orgTree() {
        OrgTree t = orgTree;
        return t == null ? null : t.copy();
    }

    /** 로그인 때 받은 사용자 목록 (등록 순서, 삭제된 사용자 없음). */
    public List<UserEntry> users() {
        return users;
    }

    /** 아이디 → 이름. 목록에 없으면(삭제된 사용자 등) 아이디 그대로. */
    public String nameOf(String id) {
        if (id == null) {
            return "";
        }
        for (UserEntry u : users) {
            if (u.getId().equals(id)) {
                return u.getName();
            }
        }
        return id;
    }

    /** "김철수 (kim)" */
    public String nameWithId(String id) {
        String name = nameOf(id);
        return name.equals(id) ? id : name + " (" + id + ")";
    }

    /** "김철수 (kim), 이영희 (lee)" */
    public String namesWithIds(List<String> ids) {
        List<String> parts = new ArrayList<>();
        for (String id : ids) {
            parts.add(nameWithId(id));
        }
        return String.join(", ", parts);
    }

    /** 보낸 메시지 목록의 받는 사람 칸: "이영희" / "이영희 외 2명" (U42). */
    public String describeReceivers(List<String> ids) {
        if (ids.isEmpty()) {
            return "";
        }
        String first = nameOf(ids.get(0));
        return ids.size() == 1 ? first : first + " 외 " + (ids.size() - 1) + "명";
    }

    // =====================================================================

    /** 메인 창 개수와 관리함 목록을 보관함 기준으로 다시 그린다. */
    private void refreshViews() {
        Mailbox box = mailbox;
        if (box == null) {
            return;
        }
        if (mainFrame != null) {
            mainFrame.setUnreadCount(box.unreadCount());
        }
        if (managerFrame != null && managerFrame.isDisplayable()) {
            managerFrame.refresh();
        }
    }

    private void track(Window w) {
        childWindows.removeIf(x -> !x.isDisplayable());
        childWindows.add(w);
    }

    /** 연결 정리. sendLogout이면 서버에 LOGOUT을 알린다. */
    private void disconnect(boolean sendLogout) {
        Heartbeat h = heartbeat;
        Connection c = connection;
        ServerApi a = api;
        heartbeat = null;
        connection = null;
        api = null;
        mailbox = null;
        users = List.of();
        orgTree = null;
        if (h != null) {
            h.stop();
        }
        if (sendLogout && a != null) {
            a.logout();
        }
        if (c != null) {
            c.close(); // 남은 패킷(LOGOUT)을 보낸 뒤 닫는다
        }
    }

    /** 로그인한 동안에만 있는 창을 모두 닫는다 (메인, 관리함, 작성·보기, 알림). */
    private void closeLoggedInWindows() {
        notifyTimer.stop();
        unnotified.clear();
        readNotifyTimer.stop();
        unnotifiedReads.clear();
        Toast.close();
        for (Window w : childWindows) {
            w.dispose();
        }
        childWindows.clear();
        if (managerFrame != null) {
            managerFrame.dispose();
            managerFrame = null;
        }
        if (mainFrame != null) {
            mainFrame.dispose();
            mainFrame = null;
        }
    }

    private void showLoginFrame(String message) {
        if (loginFrame == null) {
            loginFrame = new LoginFrame(this, settings.getServer(), settings.getUserId());
        }
        loginFrame.setVisible(true);
        if (message != null) {
            JOptionPane.showMessageDialog(loginFrame, message, "연결 끊김", JOptionPane.WARNING_MESSAGE);
        }
    }
}
