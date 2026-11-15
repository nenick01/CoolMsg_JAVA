package coolmsg.server.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.net.BindException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

import coolmsg.common.ProtocolInfo;
import coolmsg.server.ServerEventListener;
import coolmsg.server.config.ServerConfig;
import coolmsg.server.net.ServerListener;

/**
 * [서버 상태] 탭: 서버 시작/중지, 접속자 목록, 서버 로그 (설계서 3.5).
 *
 * 서버 사건(ServerEventListener)을 받아 화면에 보여 준다. 사건은 소켓 스레드에서 오므로
 * 화면을 고칠 때는 항상 SwingUtilities.invokeLater로 Swing 스레드에 맡긴다(동시성 규칙 C9).
 * 로그는 최근 1000줄만 유지한다(U29). 접속자 목록은 로그인을 구현하는 3단계에서 채운다.
 */
public class StatusPanel extends JPanel implements ServerEventListener {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LOG_LINES = 1000;

    private final ServerListener listener;
    private final JLabel stateLabel = new JLabel();
    private final JButton startStopButton = new JButton();
    private final DefaultListModel<String> onlineUsers = new DefaultListModel<>();
    private final JTextArea logArea = new JTextArea();
    private boolean running = false;

    public StatusPanel(ServerConfig config, ServerListener listener) {
        super(new BorderLayout(8, 8));
        this.listener = listener;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // 위쪽: 포트, 상태, 시작/중지 버튼
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        top.add(new JLabel("포트: " + config.getPort()));
        top.add(new JLabel("프로토콜 버전: " + ProtocolInfo.VERSION));
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD));
        top.add(stateLabel);
        top.add(startStopButton);
        startStopButton.addActionListener(e -> {
            if (running) {
                stopServer();
            } else {
                startServer();
            }
        });
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

    // =====================================================================
    // 서버 시작·중지 (Swing 스레드에서 부른다)
    // =====================================================================

    /** 접속 받기 시작. 포트가 사용 중이면 오류 창을 띄우고 중지 상태로 둔다(U25). */
    public void startServer() {
        try {
            listener.start();
        } catch (BindException e) {
            log("서버 시작 실패: 포트가 이미 사용 중입니다 (" + e.getMessage() + ")");
            JOptionPane.showMessageDialog(this,
                    "포트가 이미 사용 중이라 서버를 시작하지 못했습니다.\n"
                            + "다른 서버 프로그램이 켜져 있는지 확인하거나, config/server.txt의 port를 바꾼 뒤 다시 켜 주세요.",
                    "서버 시작 실패", JOptionPane.ERROR_MESSAGE);
        } catch (IOException e) {
            log("서버 시작 실패: " + e);
            JOptionPane.showMessageDialog(this, "서버를 시작하지 못했습니다.\n원인: " + e,
                    "서버 시작 실패", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void stopServer() {
        listener.stop();
    }

    // =====================================================================
    // ServerEventListener — 소켓 스레드에서 불린다
    // =====================================================================

    @Override
    public void onLog(String message) {
        SwingUtilities.invokeLater(() -> log(message));
    }

    @Override
    public void onServerStateChanged(boolean nowRunning) {
        SwingUtilities.invokeLater(() -> {
            running = nowRunning;
            updateState();
        });
    }

    // =====================================================================
    // 화면 (Swing 스레드 전용)
    // =====================================================================

    /** 로그 한 줄 추가. Swing 스레드에서 불러야 한다. 최근 1000줄만 남긴다. */
    public void log(String text) {
        logArea.append("[" + LocalTime.now().format(TIME) + "] " + text + "\n");
        int extra = logArea.getLineCount() - 1 - MAX_LOG_LINES; // 마지막 빈 줄 제외
        if (extra > 0) {
            try {
                logArea.replaceRange("", 0, logArea.getLineEndOffset(extra - 1));
            } catch (BadLocationException ignored) {
                // 줄 계산이 어긋나면 이번에는 자르지 않는다
            }
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
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
