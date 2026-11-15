package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import coolmsg.client.ClientController;
import coolmsg.client.model.UserEntry;
import coolmsg.common.model.OrgTree;

/**
 * 메시지 작성 창 (설계서 4.3, 5장 U40·U57·U58·U60·U61).
 *
 * - 받는 사람·참조 칸: 이름 단추(×로 빼기), 폭이 모자라면 줄 바꿈(WrapLayout), 3줄 넘으면 스크롤.
 *   [받는 사람 선택]·[참조 선택]은 체크박스 조직도 창(지금 고른 사람은 체크된 채로). 한 사람은 한 칸에만(U60).
 * - 본문은 서식 편집기(RichTextEditor, U58). 보낼 때 정화한 HTML(U59).
 * - 회신·전체회신이면 받는 사람·참조와 원본 인용이 채워져 열린다. 인용도 고칠 수 있고 커서는 맨 위(U61).
 * - 보내는 동안 버튼을 막고, 실패하면 이유를 보여 주며 창을 그대로 둔다. 다시 보내면 같은 messageId(U10·U35).
 */
public class ComposeFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    /** 받는 사람·참조 칸이 늘어나는 최대 줄 수. 이보다 많으면 스크롤한다. */
    private static final int MAX_CHIP_ROWS = 3;

    /** 작성 창을 열 때 채울 내용. */
    public static final class Draft {
        final List<String> receivers;
        final List<String> cc;
        final String bodyHtml;
        final String replyTo;
        final String title;

        public Draft(List<String> receivers, List<String> cc, String bodyHtml, String replyTo, String title) {
            this.receivers = receivers;
            this.cc = cc;
            this.bodyHtml = bodyHtml;
            this.replyTo = replyTo;
            this.title = title;
        }

        /** 새 메시지 (받는 사람만 채움). */
        public static Draft to(List<String> receivers) {
            return new Draft(receivers, List.of(), "", null, "메시지 쓰기");
        }
    }

    private final ClientController controller;
    /** 이 창의 메시지 ID. 다시 보내도 바뀌지 않는다. */
    private final String messageId = UUID.randomUUID().toString();
    private final String replyTo;
    private final RecipientField receiverField;
    private final RecipientField ccField;
    private final RichTextEditor editor = new RichTextEditor();
    private final JButton sendButton = new JButton("보내기");

    public ComposeFrame(ClientController controller, List<String> initialReceivers) {
        this(controller, Draft.to(initialReceivers));
    }

    public ComposeFrame(ClientController controller, Draft draft) {
        super(draft.title);
        this.controller = controller;
        this.replyTo = draft.replyTo;
        receiverField = new RecipientField("받는 사람", "받는 사람 선택", draft.receivers);
        ccField = new RecipientField("참조", "참조 선택", draft.cc);
        receiverField.other = ccField;
        ccField.other = receiverField;

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS)); // 두 칸의 높이가 다를 수 있다
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(receiverField);
        top.add(Box.createVerticalStrut(4));
        top.add(ccField);

        editor.setHtml(draft.bodyHtml);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(sendButton);
        sendButton.addActionListener(e -> send());

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(editor, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(620, 520);
        setMinimumSize(new Dimension(460, 340)); // 이보다 좁으면 이름 단추·서식 도구가 다 보이지 않는다
        setLocationByPlatform(true);
        SwingUtilities.invokeLater(editor::focusEditor); // 커서는 맨 위 입력 공간 (U61)
    }

    // =====================================================================
    // 받는 사람·참조 칸
    // =====================================================================

    /** "받는 사람 [이영희 ×][박민수 ×] [받는 사람 선택]" 한 줄. */
    private final class RecipientField extends JPanel {
        private static final long serialVersionUID = 1L;

        final Set<String> ids = new LinkedHashSet<>();
        final ChipPanel chips = new ChipPanel();
        final JScrollPane scroll;
        final JButton pick;
        RecipientField other; // 한 사람은 한 칸에만 (U60)

        RecipientField(String label, String pickText, List<String> initial) {
            super(new BorderLayout(6, 0));
            ids.addAll(initial);
            pick = new JButton(pickText);
            pick.addActionListener(e -> pickPeople());
            scroll = new JScrollPane(chips, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
                private static final long serialVersionUID = 1L;

                /** 높이는 내용만큼, 단 MAX_CHIP_ROWS줄까지. 폭은 창이 정한다. */
                @Override
                public Dimension getPreferredSize() {
                    Insets in = getInsets();
                    int rowHeight = new JButton("가").getPreferredSize().height + 4;
                    int content = chips.getPreferredSize().height;
                    return new Dimension(100, Math.min(content, rowHeight * MAX_CHIP_ROWS + 4) + in.top + in.bottom);
                }
            };
            scroll.getVerticalScrollBar().setUnitIncrement(12);
            scroll.getViewport().addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    SwingUtilities.invokeLater(() -> {
                        chips.revalidate();
                        revalidate();
                    });
                }
            });
            JLabel name = new JLabel(label);
            name.setPreferredSize(new Dimension(60, name.getPreferredSize().height));
            JPanel labelBox = new JPanel(new BorderLayout());
            labelBox.add(name, BorderLayout.NORTH);
            JPanel pickBox = new JPanel(new BorderLayout());
            pick.setPreferredSize(new Dimension(130, pick.getPreferredSize().height)); // 두 칸의 단추 폭을 맞춘다
            pickBox.add(pick, BorderLayout.NORTH);
            add(labelBox, BorderLayout.WEST);
            add(scroll, BorderLayout.CENTER);
            add(pickBox, BorderLayout.EAST);
            update();
        }

        /** BoxLayout이 높이를 늘리지 않게: 높이는 필요한 만큼만. */
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        void update() {
            chips.removeAll();
            if (ids.isEmpty()) {
                JLabel none = new JLabel("(없음)");
                none.setEnabled(false);
                chips.add(none);
            }
            for (String id : ids) {
                JButton chip = new JButton(controller.nameOf(id) + " ×");
                chip.setMargin(new Insets(1, 6, 1, 6));
                chip.setToolTipText(controller.nameWithId(id) + " 빼기");
                chip.addActionListener(e -> {
                    ids.remove(id);
                    update();
                });
                chips.add(chip);
            }
            chips.revalidate();
            chips.repaint();
            ComposeFrame.this.getContentPane().revalidate();
        }

        /** 체크박스 조직도 창 (U57). 지금 이 칸의 사람은 체크된 채로. 여기서 고른 사람은 다른 칸에서 빠진다(U60). */
        void pickPeople() {
            JDialog dialog = new JDialog(ComposeFrame.this, pick.getText(), true);
            List<UserEntry> users = controller.users();
            OrgCheckTree tree = new OrgCheckTree();
            OrgTree org = controller.orgTree();
            if (org != null) {
                tree.showOrg(org, users);
            } else {
                tree.showFlat(users);
            }
            tree.setChecked(ids);
            JScrollPane treeScroll = new JScrollPane(tree);
            treeScroll.setBorder(BorderFactory.createTitledBorder(org != null ? "조직도" : "조직도 (임시 표시)"));
            JButton ok = new JButton("확인");
            JButton cancel = new JButton("취소");
            ok.addActionListener(e -> {
                applyPicked(tree.checkedIds(), users);
                dialog.dispose();
            });
            cancel.addActionListener(e -> dialog.dispose());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(ok);
            buttons.add(cancel);
            JLabel hint = new JLabel("부서를 체크하면 그 부서 사람이 모두 선택됩니다.");
            hint.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
            dialog.setLayout(new BorderLayout());
            dialog.add(hint, BorderLayout.NORTH);
            dialog.add(treeScroll, BorderLayout.CENTER);
            dialog.add(buttons, BorderLayout.SOUTH);
            dialog.setSize(320, 460);
            dialog.setLocationRelativeTo(ComposeFrame.this);
            dialog.setVisible(true);
        }

        /** 선택 창 [확인]: 이미 있던 사람은 순서 유지, 새로 고른 사람은 뒤에. 다른 칸에서는 뺀다(U60). */
        void applyPicked(List<String> checked, List<UserEntry> users) {
            Set<String> chosen = new LinkedHashSet<>();
            for (String id : ids) { // 목록에 없는 아이디는 그대로 둔다
                if (checked.contains(id) || !isKnown(users, id)) {
                    chosen.add(id);
                }
            }
            chosen.addAll(checked);
            ids.clear();
            ids.addAll(chosen);
            if (other.ids.removeAll(chosen)) {
                other.update();
            }
            update();
        }

        void setEnabledAll(boolean enabled) {
            pick.setEnabled(enabled);
            for (java.awt.Component c : chips.getComponents()) {
                c.setEnabled(enabled);
            }
        }
    }

    private static boolean isKnown(List<UserEntry> users, String id) {
        for (UserEntry u : users) {
            if (u.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================

    private void send() {
        List<String> to = new ArrayList<>(receiverField.ids);
        List<String> cc = new ArrayList<>(ccField.ids);
        String html = editor.getHtml();
        String problem = ClientController.checkDraft(to, cc, html); // 서버에 보내기 전에 먼저 막는다 (U5)
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "보낼 수 없음", JOptionPane.WARNING_MESSAGE);
            return;
        }
        setSending(true);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return controller.sendMessage(messageId, to, cc, html, replyTo); // 성공이면 null, 실패면 이유
            }

            @Override
            protected void done() {
                String failure;
                try {
                    failure = get();
                } catch (InterruptedException | ExecutionException e) {
                    failure = "보내지 못했습니다: " + e;
                }
                setSending(false);
                if (failure == null) {
                    dispose();
                } else {
                    JOptionPane.showMessageDialog(ComposeFrame.this,
                            failure + "\n\n창은 그대로 두었습니다. 다시 [보내기]를 누르면 다시 보냅니다.",
                            "보내기 실패", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setSending(boolean sending) {
        sendButton.setEnabled(!sending);
        sendButton.setText(sending ? "보내는 중…" : "보내기");
        editor.setEditable(!sending);
        receiverField.setEnabledAll(!sending);
        ccField.setEnabledAll(!sending);
    }

    /**
     * 이름 단추 패널. 스크롤 창 안에서 폭은 창에 맞추고(가로 스크롤 없음) 높이만 늘어난다.
     * 그래야 WrapLayout이 "지금 폭"으로 줄을 나눌 수 있다.
     */
    private static final class ChipPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        ChipPanel() {
            super(new WrapLayout(FlowLayout.LEFT, 4, 2));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 12;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return visible.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true; // 폭은 보이는 영역에 맞춤 → 넘치면 다음 줄로
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
