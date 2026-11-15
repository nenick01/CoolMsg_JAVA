package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import coolmsg.client.net.ConnectionTester;
import coolmsg.client.net.ConnectionTester.Result;
import coolmsg.client.net.ServerAddress;

/**
 * 서버 연결 테스트 창 (설계서 4.3, 2.4).
 *
 * 테스트는 최대 6초(연결 3초 + 응답 3초) 걸릴 수 있다. Swing 스레드에서 기다리면 그동안 창이 멈추므로
 * SwingWorker로 다른 스레드에서 실행하고, 끝나면 done()에서(Swing 스레드) 결과를 보여 준다 (REQ-N-005).
 */
public class ConnectionTestDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final ServerAddress address;
    private final JLabel statusLabel = new JLabel();
    private final JLabel detailLabel = new JLabel();
    private final JButton retryButton = new JButton("다시 시도");

    public ConnectionTestDialog(JFrame owner, ServerAddress address) {
        super(owner, "서버 연결 테스트", true);
        this.address = address;

        JPanel body = new JPanel(new BorderLayout(4, 8));
        body.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
        body.add(new JLabel("대상: " + address), BorderLayout.NORTH);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        body.add(statusLabel, BorderLayout.CENTER);
        body.add(detailLabel, BorderLayout.SOUTH);

        JButton closeButton = new JButton("닫기");
        retryButton.addActionListener(e -> runTest());
        closeButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(retryButton);
        buttons.add(closeButton);

        setLayout(new BorderLayout());
        add(body, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(380, 220);
        setLocationRelativeTo(owner);

        runTest();
    }

    private void runTest() {
        retryButton.setEnabled(false);
        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setText("확인 중…");
        detailLabel.setText(" ");

        new SwingWorker<Result, Void>() {
            @Override
            protected Result doInBackground() {
                return new ConnectionTester().test(address); // Swing 스레드가 아닌 곳에서 실행
            }

            @Override
            protected void done() { // Swing 스레드에서 실행
                try {
                    showResult(get());
                } catch (InterruptedException | ExecutionException e) {
                    statusLabel.setForeground(Color.RED);
                    statusLabel.setText("✖ 테스트 중 오류");
                    detailLabel.setText(e.toString());
                }
                retryButton.setEnabled(true);
            }
        }.execute();
    }

    private void showResult(Result r) {
        if (r.isSuccess()) {
            statusLabel.setForeground(new Color(0, 140, 0));
            statusLabel.setText("✔ " + r.getOutcome().getDescription());
            detailLabel.setText("<html>서버 이름: " + r.getServerName()
                    + "<br>프로토콜 버전: " + r.getVersion()
                    + "<br>응답 시간: " + r.getMillis() + "ms</html>");
            return;
        }
        statusLabel.setForeground(Color.RED);
        statusLabel.setText("✖ " + r.getOutcome().getDescription());
        StringBuilder sb = new StringBuilder("<html>");
        if (r.getOutcome() == ConnectionTester.Outcome.VERSION_MISMATCH) {
            sb.append("서버 버전: ").append(r.getVersion()).append(", 이 프로그램: ")
                    .append(coolmsg.common.ProtocolInfo.VERSION).append("<br>");
        }
        if (r.getDetail() != null) {
            sb.append(escape(r.getDetail()));
        }
        detailLabel.setText(sb.append("</html>").toString());
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
