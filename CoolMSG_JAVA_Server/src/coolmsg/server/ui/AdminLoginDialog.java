package coolmsg.server.ui;

import java.awt.GridLayout;
import java.io.IOException;
import java.util.Arrays;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

import coolmsg.server.config.AdminConfig;

/**
 * 서버를 켤 때 관리자 확인.
 * - 관리자 비밀번호가 없으면(처음 실행) 새로 정하는 창
 * - 있으면 비밀번호를 입력하는 창 (최대 5번)
 */
public final class AdminLoginDialog {

    private static final int MAX_ATTEMPTS = 5;
    private static final int MIN_LENGTH = 4;
    private static final String TITLE = "CoolMsg 서버";

    private AdminLoginDialog() {
    }

    /** 관리자 확인에 성공하면 true, 취소하거나 5번 틀리면 false. */
    public static boolean authenticate(AdminConfig admin) {
        if (!admin.isPasswordSet()) {
            return setupPassword(admin);
        }
        return login(admin);
    }

    private static boolean setupPassword(AdminConfig admin) {
        JPasswordField pw = new JPasswordField(15);
        JPasswordField confirm = new JPasswordField(15);
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.add(new JLabel("처음 실행입니다. 관리자 비밀번호를 정해 주세요. (" + MIN_LENGTH + "자 이상)"));
        panel.add(new JLabel("비밀번호"));
        panel.add(pw);
        panel.add(new JLabel("비밀번호 확인"));
        panel.add(confirm);

        while (true) {
            int result = JOptionPane.showConfirmDialog(null, panel, TITLE + " - 관리자 비밀번호 설정",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return false;
            }
            char[] p1 = pw.getPassword();
            char[] p2 = confirm.getPassword();
            try {
                if (p1.length < MIN_LENGTH) {
                    showError("비밀번호는 " + MIN_LENGTH + "자 이상이어야 합니다.");
                } else if (!Arrays.equals(p1, p2)) {
                    showError("두 비밀번호가 다릅니다.");
                } else {
                    admin.savePassword(p1);
                    return true;
                }
            } catch (IOException e) {
                showError("관리자 비밀번호를 저장하지 못했습니다.\n" + e.getMessage());
                return false;
            } finally {
                Arrays.fill(p1, '\0'); // 메모리에 비밀번호가 남지 않게 지운다.
                Arrays.fill(p2, '\0');
            }
            pw.setText("");
            confirm.setText("");
        }
    }

    private static boolean login(AdminConfig admin) {
        JPasswordField pw = new JPasswordField(15);
        JLabel message = new JLabel("관리자 비밀번호를 입력하세요.");
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.add(message);
        panel.add(pw);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            int result = JOptionPane.showConfirmDialog(null, panel, TITLE + " - 관리자 로그인",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return false;
            }
            char[] input = pw.getPassword();
            boolean ok = admin.checkPassword(input);
            Arrays.fill(input, '\0');
            if (ok) {
                return true;
            }
            pw.setText("");
            message.setText("비밀번호가 틀렸습니다. (" + attempt + "/" + MAX_ATTEMPTS + ")");
        }
        showError("비밀번호를 " + MAX_ATTEMPTS + "번 틀려 서버를 종료합니다.");
        return false;
    }

    private static void showError(String text) {
        JOptionPane.showMessageDialog(null, text, TITLE, JOptionPane.ERROR_MESSAGE);
    }
}
