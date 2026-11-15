package coolmsg.server.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;

import coolmsg.common.ProtocolInfo;
import coolmsg.server.config.ServerConfig;

/**
 * [서버 상태] 탭: 서버 시작/중지, 접속자 목록, 서버 로그.
 *
 * 지금은 화면만 있다. 시작 버튼은 상태 표시만 바꾸고, 실제 소켓 서버는 다음 단계에서 연결한다.
 */
public class StatusPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ServerConfig config;
    private final JLabel stateLabel = new JLabel();
    private final JButton startStopButton = new JButton();
    private final DefaultListModel<String> onlineUsers = new DefaultListModel<>();
    private final JTextArea logArea = new JTextArea();
    private boolean running = false;

    public StatusPanel(ServerConfig config) {
        super(new BorderLayout(8, 8));
        this.config = config;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // 위쪽: 포트, 상태, 시작/중지 버튼
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        top.add(new JLabel("포트: " + config.getPort()));
        top.add(new JLabel("프로토콜 버전: " + ProtocolInfo.VERSION));
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD));
        top.add(stateLabel);
        top.add(startStopButton);
        startStopButton.addActionListener(e -> toggleServer());
        add(top, BorderLayout.NORTH);

        // 가운데: 왼쪽 접속자 목록, 오른쪽 로그
        JList<String> userList = new JList<>(onlineUsers);
        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(BorderFactory.createTitledBorder("접속자"));

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("서버 로그"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, userScroll, logScroll);
        split.setDividerLocation(200);
        add(split, BorderLayout.CENTER);

        updateState();
    }

    /** 로그 한 줄 추가. Swing 스레드에서 불러야 한다. */
    public void log(String text) {
        logArea.append("[" + LocalTime.now().format(TIME) + "] " + text + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void toggleServer() {
        running = !running;
        if (running) {
            log("서버 시작 (포트 " + config.getPort() + ") - 통신 기능은 구현 2단계에서 연결합니다.");
        } else {
            log("서버 중지");
        }
        updateState();
    }

    private void updateState() {
        if (running) {
            stateLabel.setText("● 실행 중");
            stateLabel.setForeground(new Color(0, 140, 0));
            startStopButton.setText("서버 중지");
        } else {
            stateLabel.setText("● 중지됨");
            stateLabel.setForeground(Color.GRAY);
            startStopButton.setText("서버 시작");
        }
    }
}
