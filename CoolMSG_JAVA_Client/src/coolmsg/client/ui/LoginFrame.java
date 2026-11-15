package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

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
 * 구현 2단계: [연결 테스트]만 동작한다. [로그인]은 3단계에서 연결한다.
 */
public class LoginFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private final JTextField serverField = new JTextField("localhost", 18);
    private final JTextField idField = new JTextField(18);
    private final JPasswordField passwordField = new JPasswordField(18);

    public LoginFrame() {
        super("CoolMsg");

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

        JButton testButton = new JButton("연결 테스트");
        JButton loginButton = new JButton("로그인");
        testButton.addActionListener(e -> testConnection());
        loginButton.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "로그인은 구현 3단계에서 연결합니다.", "준비 중", JOptionPane.INFORMATION_MESSAGE));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        buttons.add(testButton);
        buttons.add(loginButton);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(loginButton);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
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

    /** [연결 테스트]: 서버 칸을 해석하고 테스트 창을 띄운다. */
    private void testConnection() {
        ServerAddress address;
        try {
            address = ServerAddress.parse(serverField.getText());
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "서버 주소 확인", JOptionPane.WARNING_MESSAGE);
            serverField.requestFocus();
            return;
        }
        new ConnectionTestDialog(this, address).setVisible(true);
    }
}
