package coolmsg.server.ui;

import java.awt.Component;
import java.awt.GridLayout;
import java.util.Arrays;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

import coolmsg.server.config.UserDirectory;

/**
 * [사용자 관리] 탭의 입력 창들 (설계서 3.5 사용자 추가 창).
 *
 * 입력만 받고, 규칙 검사와 저장은 UserDirectory가 한다.
 * 취소하면 null을 돌려준다.
 */
final class UserDialogs {

    private UserDialogs() {
    }

    /** 사용자 추가 입력값. */
    static final class NewUser {
        final String name;
        final String id;
        final char[] password;
        final char[] confirm;

        NewUser(String name, String id, char[] password, char[] confirm) {
            this.name = name;
            this.id = id;
            this.password = password;
            this.confirm = confirm;
        }

        void clear() {
            Arrays.fill(password, '\0');
            Arrays.fill(confirm, '\0');
        }
    }

    /** 새 비밀번호 두 번 입력값. */
    static final class NewPassword {
        final char[] password;
        final char[] confirm;

        NewPassword(char[] password, char[] confirm) {
            this.password = password;
            this.confirm = confirm;
        }

        void clear() {
            Arrays.fill(password, '\0');
            Arrays.fill(confirm, '\0');
        }
    }

    /**
     * 사용자 추가 창. 이전 입력(이름·아이디)을 채워 다시 열 수 있게 prefill을 받는다.
     * (검사에 실패하면 화면이 입력을 지우지 않고 다시 보여 주기 위해)
     */
    static NewUser askNewUser(Component parent, String prefillName, String prefillId) {
        JTextField name = new JTextField(prefillName, 15);
        JTextField id = new JTextField(prefillId, 15);
        JPasswordField pw = new JPasswordField(15);
        JPasswordField confirm = new JPasswordField(15);

        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 6));
        panel.add(new JLabel("이름"));
        panel.add(name);
        panel.add(new JLabel("아이디"));
        panel.add(id);
        panel.add(new JLabel("비밀번호"));
        panel.add(pw);
        panel.add(new JLabel("비밀번호 확인"));
        panel.add(confirm);
        panel.add(new JLabel(hint()));

        int result = JOptionPane.showConfirmDialog(parent, panel, "사용자 추가",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        return new NewUser(name.getText(), id.getText().strip(), pw.getPassword(), confirm.getPassword());
    }

    /** 이름 수정 창. */
    static String askName(Component parent, String id, String currentName) {
        Object input = JOptionPane.showInputDialog(parent,
                id + "의 새 이름 (1~" + UserDirectory.NAME_MAX_LENGTH + "자, '|' 불가)",
                "이름 수정", JOptionPane.PLAIN_MESSAGE, null, null, currentName);
        return (input == null) ? null : input.toString();
    }

    /** 새 비밀번호 두 번 입력 창 (비밀번호 초기화, 복구에서 사용). */
    static NewPassword askNewPassword(Component parent, String title, String message) {
        JPasswordField pw = new JPasswordField(15);
        JPasswordField confirm = new JPasswordField(15);
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.add(new JLabel(message));
        panel.add(new JLabel("새 비밀번호 (" + UserDirectory.PASSWORD_MIN_LENGTH + "자 이상)"));
        panel.add(pw);
        panel.add(new JLabel("새 비밀번호 확인"));
        panel.add(confirm);

        int result = JOptionPane.showConfirmDialog(parent, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        return new NewPassword(pw.getPassword(), confirm.getPassword());
    }

    private static String hint() {
        return "<html><small>아이디: 영문 소문자·숫자 " + UserDirectory.ID_MIN_LENGTH + "~" + UserDirectory.ID_MAX_LENGTH
                + "자<br>비밀번호: " + UserDirectory.PASSWORD_MIN_LENGTH + "자 이상</small></html>";
    }
}
