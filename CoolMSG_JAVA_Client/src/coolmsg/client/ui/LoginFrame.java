package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import coolmsg.client.ClientController;
import coolmsg.client.ClientController.LoginOutcome;
import coolmsg.client.net.ServerAddress;

/**
 * 로그인 화면 (첫 화면, 설계서 4.3).
 *
 * <pre>
 * 서버     [ localhost        ]   ← "IP" 또는 "IP:포트" (U26)
 * 아이디   [                  ]
 * 비밀번호 [                  ]
 *    [연결 테스트]   [로그인]
 * </pre>
 *
 * 로그인은 최대 10초 이상 걸릴 수 있으므로 SwingWorker로 다른 스레드에서 하고, 그동안 버튼을 막는다.
 * 통신은 직접 하지 않고 ClientController에 맡긴다.
 */
public class LoginFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private final ClientController controller;
    private final JTextField serverField;
    private final JTextField idField;
    private final JPasswordField passwordField = new JPasswordField(18);
    private final JButton testButton = new JButton("연결 테스트");
    private final JButton loginButton = new JButton("로그인");
    private final JLabel statusLabel = new JLabel(" ");

    public LoginFrame(ClientController controller, String server, String userId) {
        super("CoolMsg");
        this.controller = controller;
        this.serverField = new JTextField(server, 18);
        this.idField = new JTextField(userId, 18);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
        addRow(form, 0, "서버", serverField);
        addRow(form, 1, "아이디", idField);
        addRow(form, 2, "비밀번호", passwordField);

        GridBagConstraints hint = new GridBagConstraints();
        hint.gridx = 1;
        hint.gridy = 3;
        hint.anchor = GridBagConstraints.WEST;
        form.add(new JLabel("<html><small>예: 192.168.0.10 또는 192.168.0.10:9100</small></html>"), hint);

        testButton.addActionListener(e -> testConnection());
        loginButton.addActionListener(e -> login());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        buttons.add(testButton);
        buttons.add(loginButton);
        JPanel south = new JPanel(new BorderLayout());
        south.add(statusLabel, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        statusLabel.setHorizontalAlignment(JLabel.CENTER);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(loginButton);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // 로그인 전이라 연결이 없으므로 바로 종료
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
        if (!userId.isEmpty()) {
            passwordField.requestFocusInWindow(); // 아이디가 채워져 있으면 비밀번호부터
        }
    }

    private static void addRow(JPanel panel, int row, String label, JTextField field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
    }

    /** 서버 칸을 해석한다. 틀리면 안내하고 null. */
    private ServerAddress parseServer() {
        try {
            return ServerAddress.parse(serverField.getText());
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "서버 주소 확인", JOptionPane.WARNING_MESSAGE);
            serverField.requestFocus();
            return null;
        }
    }

    /** [연결 테스트]: 테스트 창을 띄운다. */
    private void testConnection() {
        ServerAddress address = parseServer();
        if (address != null) {
            new ConnectionTestDialog(this, address).setVisible(true);
        }
    }

    /** [로그인]: 입력을 확인하고 SwingWorker로 로그인한다. */
    private void login() {
        ServerAddress address = parseServer();
        if (address == null) {
            return;
        }
        String userId = idField.getText().strip();
        char[] password = passwordField.getPassword();
        if (userId.isEmpty() || password.length == 0) {
            JOptionPane.showMessageDialog(this, "아이디와 비밀번호를 입력하세요.", "입력 확인", JOptionPane.WARNING_MESSAGE);
            Arrays.fill(password, '\0');
            return;
        }
        String serverText = serverField.getText();
        setBusy(true);

        new SwingWorker<LoginOutcome, Void>() {
            @Override
            protected LoginOutcome doInBackground() {
                try {
                    return controller.login(address, userId, password); // Swing 스레드가 아닌 곳에서 통신
                } finally {
                    Arrays.fill(password, '\0'); // 메모리에 비밀번호가 남지 않게
                }
            }

            @Override
            protected void done() { // Swing 스레드
                setBusy(false);
                LoginOutcome outcome;
                try {
                    outcome = get();
                } catch (InterruptedException | ExecutionException e) {
                    JOptionPane.showMessageDialog(LoginFrame.this, "로그인 중 오류: " + e, "로그인 실패",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (outcome.ok) {
                    passwordField.setText("");
                    controller.onLoginSucceeded(serverText, outcome);
                } else {
                    passwordField.setText("");
                    JOptionPane.showMessageDialog(LoginFrame.this, outcome.message, "로그인 실패",
                            JOptionPane.WARNING_MESSAGE);
                    passwordField.requestFocus();
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        testButton.setEnabled(!busy);
        statusLabel.setText(busy ? "로그인 중…" : " ");
    }
}
