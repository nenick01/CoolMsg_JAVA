package coolmsg.server.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import coolmsg.server.config.UserAccount;
import coolmsg.server.config.UserDirectory;
import coolmsg.server.config.UserDirectoryException;

/**
 * [사용자 관리] 탭 (설계서 3.5, REQ-F-203~206, 212).
 *
 * - 표(JTable)는 보여 주기만 하고, 바꾸는 일은 버튼으로 여는 창에서만 한다(실수로 값을 바꾸지 않게).
 * - 규칙 검사와 저장은 UserDirectory가 하고, 이 화면은 결과를 안내만 한다.
 *   입력 규칙 위반(UserDirectoryException)과 파일 저장 실패(IOException)를 구분해 안내한다.
 * - 삭제된 사용자도 등록 순서 자리에 회색으로 보이며 [복구]만 쓸 수 있다(U18).
 * - [새로고침]은 메모리의 목록을 다시 그리기만 한다(U23).
 */
public class UserPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final String[] COLUMNS = {"순서", "아이디", "이름", "비밀번호", "상태"};
    private static final int COL_ID = 1;

    private final UserDirectory directory;
    private final StatusPanel status;
    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);

    private final JButton addButton = new JButton("추가");
    private final JButton renameButton = new JButton("수정");
    private final JButton resetButton = new JButton("비밀번호 초기화");
    private final JButton deleteButton = new JButton("삭제");
    private final JButton restoreButton = new JButton("복구");
    private final JButton refreshButton = new JButton("새로고침");

    public UserPanel(UserDirectory directory, StatusPanel status) {
        super(new BorderLayout(8, 8));
        this.directory = directory;
        this.status = status;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.setDefaultRenderer(Object.class, new DeletedRowRenderer());
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton b : new JButton[] {addButton, renameButton, resetButton, deleteButton, restoreButton, refreshButton}) {
            buttons.add(b);
        }
        add(buttons, BorderLayout.SOUTH);

        addButton.addActionListener(e -> addUser());
        renameButton.addActionListener(e -> renameUser());
        resetButton.addActionListener(e -> resetPassword());
        deleteButton.addActionListener(e -> deleteUser());
        restoreButton.addActionListener(e -> restoreUser());
        refreshButton.addActionListener(e -> refresh(null));

        refresh(null);
    }

    // =====================================================================
    // 표
    // =====================================================================

    /** 메모리의 목록을 표에 다시 그린다. selectId가 있으면 그 줄을 선택한다. */
    private void refresh(String selectId) {
        model.setRowCount(0);
        List<UserAccount> users = directory.getUsers();
        int order = 1;
        int selectRow = -1;
        for (UserAccount u : users) {
            // 비밀번호는 해시만 있으므로 원문을 보여 줄 수 없다. 설정 여부만 표시한다.
            model.addRow(new Object[] {order, u.getId(), u.getName(), "●●●● (설정됨)", u.isDeleted() ? "삭제됨" : ""});
            if (u.getId().equals(selectId)) {
                selectRow = order - 1;
            }
            order++;
        }
        if (selectRow >= 0) {
            table.setRowSelectionInterval(selectRow, selectRow);
        }
        updateButtons();
    }

    /** 선택한 줄의 사용자. 선택이 없으면 null. */
    private UserAccount selected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return null;
        }
        return directory.find((String) model.getValueAt(row, COL_ID));
    }

    /** 선택 상태에 따라 버튼을 켜고 끈다. 삭제된 사용자는 [복구]만 가능. */
    private void updateButtons() {
        UserAccount u = selected();
        boolean active = u != null && !u.isDeleted();
        boolean deleted = u != null && u.isDeleted();
        renameButton.setEnabled(active);
        resetButton.setEnabled(active);
        deleteButton.setEnabled(active);
        restoreButton.setEnabled(deleted);
    }

    // =====================================================================
    // 버튼 동작
    // =====================================================================

    private void addUser() {
        String name = "";
        String id = "";
        while (true) {
            UserDialogs.NewUser input = UserDialogs.askNewUser(this, name, id);
            if (input == null) {
                return; // 취소
            }
            name = input.name;
            id = input.id;
            try {
                UserDirectory.checkSame(input.password, input.confirm);
                directory.add(input.id, input.name, input.password);
                status.log("사용자 추가: " + input.id);
                refresh(input.id);
                return;
            } catch (UserDirectoryException e) {
                showInputError(e); // 이름·아이디는 채운 채로 다시 연다
            } catch (IOException e) {
                showSaveError(e);
                return;
            } finally {
                input.clear();
            }
        }
    }

    private void renameUser() {
        UserAccount u = selected();
        if (u == null) {
            return;
        }
        String name = u.getName();
        while (true) {
            name = UserDialogs.askName(this, u.getId(), name);
            if (name == null) {
                return;
            }
            try {
                directory.rename(u.getId(), name);
                status.log("이름 수정: " + u.getId());
                refresh(u.getId());
                return;
            } catch (UserDirectoryException e) {
                showInputError(e);
            } catch (IOException e) {
                showSaveError(e);
                return;
            }
        }
    }

    private void resetPassword() {
        UserAccount u = selected();
        if (u == null) {
            return;
        }
        askPasswordAndRun("비밀번호 초기화", u.getId() + "(" + u.getName() + ")의 비밀번호를 새로 정합니다.",
                pw -> directory.resetPassword(u.getId(), pw), "비밀번호 초기화: " + u.getId(), u.getId());
    }

    private void deleteUser() {
        UserAccount u = selected();
        if (u == null) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                u.getId() + "(" + u.getName() + ")를 삭제할까요?\n"
                        + "삭제한 아이디는 다른 사람에게 다시 쓸 수 없고, [복구]로만 되살릴 수 있습니다.",
                "사용자 삭제", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            directory.delete(u.getId());
            status.log("사용자 삭제: " + u.getId());
            refresh(u.getId());
        } catch (UserDirectoryException e) {
            showInputError(e);
        } catch (IOException e) {
            showSaveError(e);
        }
    }

    private void restoreUser() {
        UserAccount u = selected();
        if (u == null) {
            return;
        }
        askPasswordAndRun("사용자 복구", u.getId() + "(" + u.getName() + ")를 복구합니다. 새 비밀번호를 정해 주세요.",
                pw -> directory.restore(u.getId(), pw), "사용자 복구: " + u.getId(), u.getId());
    }

    /** 새 비밀번호를 두 번 받아 action을 실행한다. 규칙 위반이면 다시 묻는다. */
    private void askPasswordAndRun(String title, String message, PasswordAction action, String logMessage,
            String selectId) {
        while (true) {
            UserDialogs.NewPassword input = UserDialogs.askNewPassword(this, title, message);
            if (input == null) {
                return;
            }
            try {
                UserDirectory.checkSame(input.password, input.confirm);
                action.run(input.password);
                status.log(logMessage);
                refresh(selectId);
                return;
            } catch (UserDirectoryException e) {
                showInputError(e);
            } catch (IOException e) {
                showSaveError(e);
                return;
            } finally {
                input.clear();
            }
        }
    }

    /** 새 비밀번호로 실행할 동작 (초기화, 복구). */
    private interface PasswordAction {
        void run(char[] password) throws UserDirectoryException, IOException;
    }

    private void showInputError(UserDirectoryException e) {
        JOptionPane.showMessageDialog(this, e.getMessage(), "입력을 확인해 주세요", JOptionPane.WARNING_MESSAGE);
    }

    private void showSaveError(IOException e) {
        status.log("저장 실패: " + e);
        JOptionPane.showMessageDialog(this,
                "사용자 목록을 저장하지 못해 변경을 취소했습니다.\n원인: " + e,
                "저장 실패", JOptionPane.ERROR_MESSAGE);
        refresh(null);
    }

    // =====================================================================
    // 삭제된 사용자를 회색으로
    // =====================================================================

    private class DeletedRowRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
            boolean deleted = "삭제됨".equals(model.getValueAt(row, COLUMNS.length - 1));
            if (!isSelected) {
                c.setForeground(deleted ? Color.GRAY : t.getForeground());
            }
            return c;
        }
    }
}
