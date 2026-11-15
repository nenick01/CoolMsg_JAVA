package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import coolmsg.client.ClientController;
import coolmsg.client.model.MailItem;
import coolmsg.common.model.HtmlText;

/**
 * 메시지 관리함: [받은 메시지] / [보낸 메시지] 탭이 있는 별도 창 (설계서 4.3, 5장 U39·U42).
 *
 * - 최신이 위. 안 읽은 받은 메시지는 굵게.
 * - 받은 메시지: 보낸 사람 | 날짜·시간 | 내용 미리보기(30자)
 * - 보낸 메시지: 받는 사람("이영희 외 2명") | 날짜·시간 | 내용 미리보기 (읽음 요약은 5단계)
 * - [열기](두 번 누르기도 같음), [삭제](확인 창). 삭제는 내 PC 보관함에서만.
 * 목록 내용은 컨트롤러가 refresh()로 다시 채운다. Swing 스레드에서만 쓴다.
 */
public class MessageManagerFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    public static final int PREVIEW_CHARS = 30;

    private final ClientController controller;
    private final JTabbedPane tabs = new JTabbedPane();
    private final MailTableModel inboxModel;
    private final MailTableModel sentModel;
    private final JTable inboxTable;
    private final JTable sentTable;

    public MessageManagerFrame(ClientController controller) {
        super("메시지 관리함");
        this.controller = controller;

        inboxModel = new MailTableModel(true);
        sentModel = new MailTableModel(false);
        inboxTable = createTable(inboxModel);
        sentTable = createTable(sentModel);
        tabs.addTab("받은 메시지", new JScrollPane(inboxTable));
        tabs.addTab("보낸 메시지", new JScrollPane(sentTable));

        JButton open = new JButton("열기");
        JButton delete = new JButton("삭제");
        open.addActionListener(e -> openSelected());
        delete.addActionListener(e -> deleteSelected());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(open);
        buttons.add(delete);

        setLayout(new BorderLayout());
        add(tabs, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(640, 420);
        setLocationByPlatform(true);
        refresh();
    }

    /** 보관함에서 목록을 다시 읽는다. 고른 줄은 같은 메시지면 유지한다. */
    public void refresh() {
        reload(inboxTable, inboxModel, controller.mailbox().inbox());
        reload(sentTable, sentModel, controller.mailbox().sent());
        int unread = 0;
        for (MailItem m : inboxModel.items) {
            if (!m.isRead()) {
                unread++;
            }
        }
        tabs.setTitleAt(0, unread > 0 ? "받은 메시지 (" + unread + ")" : "받은 메시지");
    }

    /** 받은 메시지 탭(true) 또는 보낸 메시지 탭을 보여 준다. */
    public void showTab(boolean inbox) {
        tabs.setSelectedIndex(inbox ? 0 : 1);
    }

    // =====================================================================

    private boolean inboxSelected() {
        return tabs.getSelectedIndex() == 0;
    }

    private MailItem selectedItem() {
        JTable table = inboxSelected() ? inboxTable : sentTable;
        MailTableModel model = inboxSelected() ? inboxModel : sentModel;
        int row = table.getSelectedRow();
        return row < 0 ? null : model.items.get(table.convertRowIndexToModel(row));
    }

    private void openSelected() {
        MailItem item = selectedItem();
        if (item != null) {
            controller.openMessage(item, inboxSelected());
        }
    }

    private void deleteSelected() {
        MailItem item = selectedItem();
        if (item == null) {
            return;
        }
        boolean inbox = inboxSelected();
        int answer = JOptionPane.showConfirmDialog(this,
                "이 메시지를 지울까요?\n내 PC의 보관함에서만 지워지며, 되돌릴 수 없습니다.",
                "메시지 삭제", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            controller.deleteMessage(item.getMessageId(), inbox);
        }
    }

    private JTable createTable(MailTableModel model) {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(130);
        table.getColumnModel().getColumn(2).setPreferredWidth(340);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                    boolean focus, int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                MailItem item = model.items.get(t.convertRowIndexToModel(row));
                c.setFont(c.getFont().deriveFont(item.isRead() ? Font.PLAIN : Font.BOLD)); // 안 읽음: 굵게
                return c;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    openSelected();
                }
            }
        });
        return table;
    }

    private static void reload(JTable table, MailTableModel model, List<MailItem> items) {
        int row = table.getSelectedRow();
        String selectedId = row < 0 ? null : model.items.get(table.convertRowIndexToModel(row)).getMessageId();
        model.setItems(items);
        if (selectedId != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getMessageId().equals(selectedId)) {
                    table.setRowSelectionInterval(i, i);
                    break;
                }
            }
        }
    }

    /** 목록 표 내용: 보관함 메시지 목록을 열 세 개로 보여 준다. */
    private final class MailTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;

        private final boolean inbox;
        private List<MailItem> items = new ArrayList<>();

        MailTableModel(boolean inbox) {
            this.inbox = inbox;
        }

        void setItems(List<MailItem> newItems) {
            items = new ArrayList<>(newItems);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case 0:
                    return inbox ? "보낸 사람" : "받는 사람";
                case 1:
                    return "날짜/시간";
                default:
                    return "내용";
            }
        }

        @Override
        public Object getValueAt(int row, int column) {
            MailItem m = items.get(row);
            switch (column) {
                case 0:
                    return inbox ? controller.nameOf(m.getData().getSender())
                            : controller.describeReceivers(m.getData().getReceivers());
                case 1:
                    return m.sentAtText();
                default:
                    return HtmlText.preview(m.getData().getBody(), PREVIEW_CHARS);
            }
        }
    }
}
