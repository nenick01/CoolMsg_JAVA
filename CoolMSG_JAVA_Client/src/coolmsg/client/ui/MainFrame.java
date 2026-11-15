package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import coolmsg.client.ClientController;
import coolmsg.client.model.UserEntry;
import coolmsg.common.model.OrgTree;

/**
 * 메인 화면 (설계서 4.3, 5장 U39~U41·U53). 체크박스 조직도 + [메시지 쓰기] [메시지 관리함 (n)].
 *
 * - 처음에는 LOGIN_OK의 사용자 목록(등록 순서)으로 열고, 조직도(ORG_OK)가 오면 부서 트리로 바꾼다(2.10).
 *   조직도를 받지 못하면(ORG_NONE, 3초 초과, 형식 오류) 목록 그대로 두고 위에 "임시 표시" 안내를 보인다.
 * - 부서·사람을 체크하고 [메시지 쓰기]를 누르면 체크된 사람이 받는 사람으로 채워지고, 체크는 풀린다(U53).
 * - 접속 상태(●/○)는 PRESENCE로 바로 바뀐다.
 * - [메시지 관리함 (n)]: n은 안 읽은 받은 메시지 수(U41).
 * - 메뉴 [로그아웃]은 로그인 화면으로, [종료]는 프로그램 종료.
 * - X 버튼은 ClientController.requestCloseMainWindow()만 부른다(U33, 나중에 트레이로 바꿀 자리).
 */
public class MainFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private final JButton managerButton = new JButton("메시지 관리함");
    private final OrgCheckTree orgTree = new OrgCheckTree();
    private final JScrollPane scroll = new JScrollPane(orgTree);
    private final JLabel notice = new JLabel();

    public MainFrame(ClientController controller, String userId, List<UserEntry> users) {
        super("CoolMsg - " + displayName(userId, users));

        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("파일");
        JMenuItem logout = new JMenuItem("로그아웃");
        JMenuItem exit = new JMenuItem("종료");
        logout.addActionListener(e -> controller.logout());
        exit.addActionListener(e -> controller.shutdown());
        file.add(logout);
        file.addSeparator();
        file.add(exit);
        bar.add(file);
        setJMenuBar(bar);

        orgTree.showFlat(users); // 조직도가 오기 전까지 (2.10)
        scroll.setBorder(BorderFactory.createTitledBorder("조직도"));

        JLabel welcome = new JLabel(displayName(userId, users) + "님, 로그인했습니다.");
        welcome.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        notice.setForeground(new Color(0xB2, 0x6A, 0x00));
        notice.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        notice.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.add(welcome, BorderLayout.NORTH);
        top.add(notice, BorderLayout.SOUTH);

        JButton compose = new JButton("메시지 쓰기");
        compose.addActionListener(e -> {
            List<String> chosen = orgTree.checkedIds();
            orgTree.clearChecks(); // 다음에 쓸 때 이전 선택이 섞이지 않게 (U53)
            controller.openCompose(chosen);
        });
        managerButton.addActionListener(e -> controller.openManager(true));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        buttons.add(compose);
        buttons.add(managerButton);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                controller.requestCloseMainWindow(); // 종료 경로는 컨트롤러 한 곳 (U33)
            }
        });

        setSize(360, 560);
        setLocationRelativeTo(null);
    }

    /** 조직도를 받았을 때 (ORG_OK). users는 지금의 사용자 목록(접속 상태 반영). 체크는 새로 시작한다. */
    public void showOrg(OrgTree org, List<UserEntry> users) {
        orgTree.showOrg(org, users);
        notice.setVisible(false);
    }

    /** 조직도를 받지 못했을 때: 등록 순서 목록 + 안내 (2.10, REQ-F-119). */
    public void showTemporary(List<UserEntry> users) {
        orgTree.showFlat(users);
        notice.setText("⚠ 조직도 설정을 받지 못해 등록 순서로 표시합니다.");
        notice.setVisible(true);
        scroll.setBorder(BorderFactory.createTitledBorder("조직도 (임시 표시)"));
    }

    /** 접속 상태가 바뀐 사람만 ●/○를 바꾼다 (PRESENCE, 설계서 2.5). 목록에 없는 사람은 무시(U50). */
    public void setOnline(String userId, boolean online) {
        orgTree.setOnline(userId, online);
    }

    /** 안 읽은 받은 메시지 수를 버튼에 보여 준다: [메시지 관리함 (3)]. */
    public void setUnreadCount(int count) {
        managerButton.setText(count > 0 ? "메시지 관리함 (" + count + ")" : "메시지 관리함");
    }

    private static String displayName(String userId, List<UserEntry> users) {
        for (UserEntry u : users) {
            if (u.getId().equals(userId)) {
                return u.getName() + " (" + userId + ")";
            }
        }
        return userId;
    }
}
