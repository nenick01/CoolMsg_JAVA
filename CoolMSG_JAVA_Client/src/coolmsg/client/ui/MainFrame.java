package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import coolmsg.client.ClientController;
import coolmsg.client.model.UserEntry;

/**
 * 메인 화면 (설계서 4.3, 5장 U39~U41). 구현 4단계: 사용자 목록 + [메시지 쓰기] [메시지 관리함 (n)].
 *
 * - 사용자 목록은 LOGIN_OK로 받은 것을 등록 순서 그대로 보여 준다. 조직도(6단계)가 없으므로
 *   설계서 2.10의 "임시 표시"(등록 순서 한 줄 목록)에 해당한다. 접속 상태의 실시간 갱신(PRESENCE)은 5단계.
 * - 목록에서 여러 명을 고르고(Ctrl·Shift 클릭) [메시지 쓰기]를 누르면 그 사람들이 받는 사람으로 채워진다(U40).
 * - [메시지 관리함 (n)]: n은 안 읽은 받은 메시지 수(U41).
 * - 메뉴 [로그아웃]은 로그인 화면으로, [종료]는 프로그램 종료.
 * - X 버튼은 ClientController.requestCloseMainWindow()만 부른다(U33, 나중에 트레이로 바꿀 자리).
 */
public class MainFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private final JButton managerButton = new JButton("메시지 관리함");

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

        DefaultListModel<UserEntry> model = new DefaultListModel<>();
        for (UserEntry u : users) {
            model.addElement(u);
        }
        JList<UserEntry> list = new JList<>(model);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createTitledBorder("사용자 (등록 순서, 조직도는 구현 6단계)"));

        JLabel welcome = new JLabel(displayName(userId, users) + "님, 로그인했습니다.");
        welcome.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton compose = new JButton("메시지 쓰기");
        compose.addActionListener(e -> {
            List<String> chosen = new ArrayList<>();
            for (UserEntry u : list.getSelectedValuesList()) {
                chosen.add(u.getId());
            }
            controller.openCompose(chosen);
        });
        managerButton.addActionListener(e -> controller.openManager(true));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        buttons.add(compose);
        buttons.add(managerButton);

        setLayout(new BorderLayout());
        add(welcome, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                controller.requestCloseMainWindow(); // 종료 경로는 컨트롤러 한 곳 (U33)
            }
        });

        setSize(360, 520);
        setLocationRelativeTo(null);
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
