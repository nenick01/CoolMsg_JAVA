package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import coolmsg.client.ClientController;
import coolmsg.client.model.MailItem;
import coolmsg.common.model.DeliveryState;
import coolmsg.common.model.HtmlText;
import coolmsg.common.model.MessageData;

/**
 * 메시지 관리함: [받은 메시지] / [보낸 메시지] 탭, 왼쪽 목록 + 오른쪽 본문 (설계서 4.3, 5장 U39·U42·U48).
 *
 * - 왼쪽 목록: 최신이 위, 안 읽은 받은 메시지는 굵게.
 *   받은 메시지: 보낸 사람 | 날짜·시간 | 내용 미리보기(30자) / 보낸 메시지: 받는 사람("이영희 외 2명") | 날짜·시간 | 내용
 * - 줄을 고르면 오른쪽에 머리글(보낸 사람, 받는 사람, 참조, 보낸 시각)과 본문. 별도 보기 창은 없다(U48).
 * - 받은 메시지는 고르는 즉시 읽음 (5단계에서 이때 READ).
 * - 보낸 메시지: 목록에 읽음 요약 열(1/3), 오른쪽 본문 아래에 받는 사람별 읽음 확인. 고를 때와 [새로고침] 때 STATUS로 맞춘다(5단계).
 * - 아래 단추: [새로고침](보낸 메시지 탭) [삭제](확인 창, 내 PC에서만) [닫기]. 회신·전달은 7단계.
 * 목록은 컨트롤러가 refresh()로 다시 채운다. Swing 스레드에서만 쓴다.
 */
public class MessageManagerFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    public static final int PREVIEW_CHARS = 30;
    private static final String EMPTY_HTML =
            "<html><body style='font-family:sans-serif; color:#888888; padding:16px'>"
                    + "메시지를 고르면 여기에 내용이 보입니다.</body></html>";

    private final ClientController controller;
    private final JTabbedPane tabs = new JTabbedPane();
    private final MailTableModel inboxModel = new MailTableModel(true);
    private final MailTableModel sentModel = new MailTableModel(false);
    private final JTable inboxTable;
    private final JTable sentTable;
    private final JEditorPane detail = new JEditorPane("text/html", EMPTY_HTML);
    private final JButton deleteButton = new JButton("삭제");
    private final JButton refreshButton = new JButton("새로고침");
    /** 오른쪽에 지금 보여 주는 메시지 ID와 HTML (같으면 다시 그리지 않아 스크롤 위치를 지킨다). */
    private String shownId;
    private String shownHtml;
    /** 목록을 다시 채우는 중 (그동안 선택이 잠깐 풀리는 이벤트는 무시한다 — 읽던 본문의 스크롤이 튀지 않게). */
    private boolean reloading;

    public MessageManagerFrame(ClientController controller) {
        super("메시지 관리함");
        this.controller = controller;

        inboxTable = createTable(inboxModel);
        sentTable = createTable(sentModel);
        tabs.addTab("받은 메시지", new JScrollPane(inboxTable));
        tabs.addTab("보낸 메시지", new JScrollPane(sentTable));
        tabs.addChangeListener(e -> showSelected());

        detail.setEditable(false);
        JScrollPane detailScroll = new JScrollPane(detail);
        detailScroll.setMinimumSize(new Dimension(260, 200));
        tabs.setMinimumSize(new Dimension(300, 200));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, detailScroll);
        split.setResizeWeight(0.45); // 창을 키우면 오른쪽(본문)이 조금 더 커진다
        split.setDividerLocation(430);
        split.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));

        JButton close = new JButton("닫기");
        deleteButton.addActionListener(e -> deleteSelected());
        refreshButton.addActionListener(e -> {
            MailItem item = selectedItem();
            if (item != null && !inboxSelected()) {
                controller.requestStatus(item.getMessageId());
            }
        });
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(refreshButton);
        buttons.add(deleteButton);
        buttons.add(close);

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(960, 580);
        setMinimumSize(new Dimension(640, 360));
        setLocationByPlatform(true);
        refresh();
    }

    /** 보관함에서 목록을 다시 읽는다. 고른 줄은 같은 메시지면 유지한다. */
    public void refresh() {
        reloading = true;
        try {
            reload(inboxTable, inboxModel, controller.mailbox().inbox());
            reload(sentTable, sentModel, controller.mailbox().sent());
        } finally {
            reloading = false;
        }
        int unread = 0;
        for (MailItem m : inboxModel.items) {
            if (!m.isRead()) {
                unread++;
            }
        }
        tabs.setTitleAt(0, unread > 0 ? "받은 메시지 (" + unread + ")" : "받은 메시지");
        setTitle("메시지 관리함 (받은 메시지 " + inboxModel.items.size() + "개, 안 읽음 " + unread + "개)");
        showSelected();
    }

    /** 받은 메시지 탭(true) 또는 보낸 메시지 탭을 보여 준다. */
    public void showTab(boolean inbox) {
        tabs.setSelectedIndex(inbox ? 0 : 1);
    }

    /** 그 메시지 줄을 고른다 (알림 창을 눌렀을 때). 받은 메시지면 읽음이 된다. */
    public void select(String messageId, boolean inbox) {
        showTab(inbox);
        JTable table = inbox ? inboxTable : sentTable;
        MailTableModel model = inbox ? inboxModel : sentModel;
        int row = model.indexOf(messageId);
        if (row >= 0) {
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
        }
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

    /** 고른 줄의 내용을 오른쪽에 보여 준다. 안 읽은 받은 메시지면 읽음으로 표시한다(U48). */
    private void showSelected() {
        MailItem item = selectedItem();
        boolean inbox = inboxSelected();
        deleteButton.setEnabled(item != null);
        refreshButton.setVisible(!inbox);
        refreshButton.setEnabled(item != null);
        if (item == null) {
            if (shownId != null) {
                shownId = null;
                shownHtml = null;
                detail.setText(EMPTY_HTML);
            }
            return;
        }
        String html = toHtml(item, inbox);
        boolean newMessage = !item.getMessageId().equals(shownId);
        if (newMessage || !html.equals(shownHtml)) { // 다른 메시지이거나 읽음 상태가 바뀜
            shownId = item.getMessageId();
            shownHtml = html;
            detail.setText(html);
            detail.setCaretPosition(0); // 맨 위부터
        }
        if (newMessage && !inbox) {
            controller.requestStatus(item.getMessageId()); // 보낸 메시지를 열 때 최신 상태로 (2.6)
        }
        if (inboxSelected() && !item.isRead()) {
            controller.markRead(item.getMessageId()); // → 컨트롤러가 refresh()를 부른다
        }
    }

    /** 오른쪽 영역 HTML: 머리글 표 + 본문. 이름은 특수 문자를 바꿔 넣는다(본문만 HTML). */
    private String toHtml(MailItem item, boolean inbox) {
        MessageData d = item.getData();
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:sans-serif; margin:0'>");
        sb.append("<table width='100%' cellpadding='4' cellspacing='0' bgcolor='#F3F6FA'>");
        row(sb, "보낸 사람", controller.nameWithId(d.getSender()));
        row(sb, "받는 사람", controller.namesWithIds(d.getReceivers()));
        if (!d.getCc().isEmpty()) {
            row(sb, "참조", controller.namesWithIds(d.getCc()));
        }
        row(sb, "보낸 시각", d.getSentAt() == null ? "" : MailItem.LIST_TIME.format(d.getSentAt()));
        sb.append("</table><hr>");
        sb.append("<div style='padding:6px'>").append(d.getBody()).append("</div>");
        if (!inbox) {
            appendReadStatus(sb, item);
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /** 보낸 메시지 본문 아래 읽음 확인 (설계서 4.3): ● 읽음 시각 / ○ 안 읽음 / ○ 전송됨 / 삭제된 사용자. */
    private void appendReadStatus(StringBuilder sb, MailItem item) {
        sb.append("<hr><div style='padding:6px'><b>읽음 확인 (").append(item.readSummary()).append(")</b>");
        sb.append("<table cellpadding='2' cellspacing='0'>");
        for (String id : item.recipients()) {
            String mark;
            String text;
            if (item.isDeletedUser(id)) {
                mark = "&nbsp;";
                text = "<font color='#999999'>삭제된 사용자</font>";
            } else if (item.stateOf(id) == DeliveryState.READ) {
                mark = "<font color='#2E7D32'>●</font>";
                text = "읽음 &nbsp;" + item.readAtText(id);
            } else if (item.stateOf(id) == DeliveryState.DELIVERED) {
                mark = "○";
                text = "안 읽음";
            } else {
                mark = "○";
                text = "<font color='#999999'>전송됨 (아직 받지 않음)</font>";
            }
            String who = HtmlText.fromPlain(controller.nameOf(id));
            if (item.getData().getCc().contains(id) && !item.getData().getReceivers().contains(id)) {
                who += " (참조)";
            }
            sb.append("<tr><td>").append(mark).append("</td><td>").append(who).append("</td><td>")
                    .append(text).append("</td></tr>");
        }
        sb.append("</table></div>");
    }

    private static void row(StringBuilder sb, String label, String value) {
        sb.append("<tr><td width='70' valign='top'><b>").append(label).append("</b></td><td>")
                .append(HtmlText.fromPlain(value)).append("</td></tr>");
    }

    private void deleteSelected() {
        MailItem item = selectedItem();
        if (item == null) {
            return;
        }
        boolean inbox = inboxSelected();
        JTable table = inbox ? inboxTable : sentTable;
        int row = table.getSelectedRow();
        int answer = JOptionPane.showConfirmDialog(this,
                "이 메시지를 지울까요?\n내 PC의 보관함에서만 지워지며, 되돌릴 수 없습니다.",
                "메시지 삭제", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        controller.deleteMessage(item.getMessageId(), inbox); // → refresh()
        int count = table.getRowCount();
        if (count > 0) { // 바로 아래 줄(없으면 위 줄)을 고른다
            int next = Math.min(row, count - 1);
            table.setRowSelectionInterval(next, next);
        }
    }

    private JTable createTable(MailTableModel model) {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(table.getRowHeight() + 6);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(125);
        table.getColumnModel().getColumn(2).setPreferredWidth(180);
        if (table.getColumnCount() > 3) {
            table.getColumnModel().getColumn(3).setPreferredWidth(45); // 읽음 요약
        }
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
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !reloading) {
                showSelected();
            }
        });
        return table;
    }

    private static void reload(JTable table, MailTableModel model, List<MailItem> items) {
        int row = table.getSelectedRow();
        String selectedId = row < 0 ? null : model.items.get(table.convertRowIndexToModel(row)).getMessageId();
        model.setItems(items);
        int again = selectedId == null ? -1 : model.indexOf(selectedId);
        if (again >= 0) {
            table.setRowSelectionInterval(again, again);
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

        int indexOf(String messageId) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getMessageId().equals(messageId)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return inbox ? 3 : 4; // 보낸 메시지에는 읽음 요약 열 (5단계)
        }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case 0:
                    return inbox ? "보낸 사람" : "받는 사람";
                case 1:
                    return "날짜/시간";
                case 2:
                    return "내용";
                default:
                    return "읽음";
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
                case 2:
                    return HtmlText.preview(m.getData().getBody(), PREVIEW_CHARS);
                default:
                    return m.readSummary();
            }
        }
    }
}
