package coolmsg.server.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import coolmsg.server.config.UserAccount;
import coolmsg.server.config.UserStore;

/**
 * [사용자 관리] 탭: 사용자 목록을 표(JTable)로 보여 준다.
 *
 * 지금은 users.txt를 읽어 표시만 한다. 추가·수정·비밀번호 초기화·삭제는 다음 단계에서 구현한다.
 */
public class UserPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final String[] COLUMNS = {"순서", "아이디", "이름", "비밀번호"};

    private final UserStore store;
    private final StatusPanel status;
    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return false; // 표에서 직접 고치지 않고, 버튼으로 여는 창에서만 고친다.
        }
    };

    public UserPanel(UserStore store, StatusPanel status) {
        super(new BorderLayout(8, 8));
        this.store = store;
        this.status = status;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (String label : new String[] {"추가", "수정", "비밀번호 초기화", "삭제"}) {
            JButton button = new JButton(label);
            button.addActionListener(e -> notYet(label));
            buttons.add(button);
        }
        JButton reload = new JButton("새로고침");
        reload.addActionListener(e -> reload());
        buttons.add(reload);
        add(buttons, BorderLayout.SOUTH);

        reload();
    }

    /** users.txt를 다시 읽어 표를 채운다. */
    public void reload() {
        model.setRowCount(0);
        try {
            store.load();
        } catch (IOException e) {
            status.log("사용자 목록을 읽지 못했습니다: " + e.getMessage());
            return;
        }
        int order = 1;
        for (UserAccount user : store.getUsers()) {
            // 비밀번호는 해시만 있으므로 원문을 보여 줄 수 없다. 설정 여부만 표시한다.
            model.addRow(new Object[] {order++, user.getId(), user.getName(), "●●●● (설정됨)"});
        }
        for (String warning : store.getWarnings()) {
            status.log("[사용자 목록] " + warning);
        }
        status.log("사용자 " + store.getUsers().size() + "명을 불러왔습니다.");
    }

    private void notYet(String feature) {
        JOptionPane.showMessageDialog(this, "'" + feature + "' 기능은 다음 단계에서 구현합니다.",
                "준비 중", JOptionPane.INFORMATION_MESSAGE);
    }
}
