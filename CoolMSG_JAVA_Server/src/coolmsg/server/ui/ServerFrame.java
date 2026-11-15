package coolmsg.server.ui;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;

import coolmsg.server.ServerContext;

/**
 * 서버 관리 메인 창. 탭 3개: 서버 상태 / 사용자 관리 / 조직도 관리.
 */
public class ServerFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private final ServerContext context;
    private final StatusPanel statusPanel;

    public ServerFrame(ServerContext context) {
        super("CoolMsg 서버 관리 - " + context.getServerConfig().getServerName());
        this.context = context;

        statusPanel = new StatusPanel(context.getServerConfig(), context.getServerListener(),
                context.getUserDirectory());
        UserPanel userPanel = new UserPanel(context.getUserDirectory(), context.getSessionRegistry(), statusPanel);
        context.getEvents().addTarget(statusPanel); // 서버 사건을 이 화면들로
        context.getEvents().addTarget(userPanel);
        OrgPanel orgPanel = new OrgPanel();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("서버 상태", statusPanel);
        tabs.addTab("사용자 관리", userPanel);
        tabs.addTab("조직도 관리", orgPanel);
        setContentPane(tabs);

        // X 버튼을 눌러도 바로 꺼지지 않고 한 번 확인한다. (접속 중인 사용자가 모두 끊기기 때문)
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int answer = JOptionPane.showConfirmDialog(ServerFrame.this,
                        "서버를 종료할까요?\n접속 중인 사용자의 연결이 모두 끊어집니다.",
                        "서버 종료", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer == JOptionPane.YES_OPTION) {
                    ServerFrame.this.context.getServerListener().stop(); // 모든 연결에 BYE
                    // BYE가 전달되고 연결이 다 닫힌 뒤 정상 종료 표시(.clean)를 만들 때까지 (3.8.6)
                    ServerFrame.this.context.getServerListener().awaitStopped(5000);
                    dispose();
                    System.exit(0);
                }
            }
        });

        setSize(900, 600);
        setLocationRelativeTo(null); // 화면 가운데

        statusPanel.log("관리자 로그인 완료. 서버 관리 화면을 시작합니다.");
        for (String warning : context.getStartupWarnings()) {
            statusPanel.log("경고: " + warning);
        }
        statusPanel.log("사용자 " + context.getUserDirectory().getUsers().size() + "명을 불러왔습니다.");
    }

    /** 접속 받기를 시작한다. 화면을 띄운 뒤 부른다 (관리 화면이 열리면 자동 시작, U25). */
    public void startServer() {
        statusPanel.startServer();
    }
}
