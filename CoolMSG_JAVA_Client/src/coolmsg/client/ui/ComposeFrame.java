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
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import coolmsg.client.ClientController;
import coolmsg.client.model.UserEntry;
import coolmsg.common.model.OrgTree;

/**
 * 메시지 작성 창 (설계서 4.3, 5장 U40). 구현 4단계: 받는 사람, 본문(일반 텍스트), [보내기].
 *
 * - 받는 사람은 목록에서 고른다(직접 입력 없음 → 오타 없음). 이름 옆 ×로 뺀다.
 * - 본문은 일반 텍스트로 쓰고, 보낼 때 HTML로 바꾼다(U37, 컨트롤러가 한다).
 * - 보내는 동안 버튼을 막는다. 실패하면 이유를 보여 주고 창을 그대로 둔다.
 * - 이 창에서 다시 보내면 같은 messageId를 쓴다(서버가 중복을 거름, U10·U35).
 * 서식 툴바·참조 칸·회신은 7단계.
 */
public class ComposeFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private final ClientController controller;
    /** 이 창의 메시지 ID. 다시 보내도 바뀌지 않는다. */
    private final String messageId = UUID.randomUUID().toString();
    private final Set<String> receivers = new LinkedHashSet<>();
    /** 받는 사람 단추들. 폭이 모자라면 다음 줄로 넘어간다(WrapLayout). */
    private final ChipPanel chips = new ChipPanel();
    /** 받는 사람 칸: 최대 MAX_CHIP_ROWS줄까지 늘어나고, 그보다 많으면 세로 스크롤. */
    private final JScrollPane chipsScroll;

    /** 받는 사람 칸이 늘어나는 최대 줄 수. 이보다 많으면 스크롤한다. */
    private static final int MAX_CHIP_ROWS = 3;
    private final JTextArea body = new JTextArea();
    private final JButton sendButton = new JButton("보내기");
    private final JButton pickButton = new JButton("받는 사람 선택");

    public ComposeFrame(ClientController controller, List<String> initialReceivers) {
        super("메시지 쓰기");
        this.controller = controller;
        receivers.addAll(initialReceivers);

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        chipsScroll = new JScrollPane(chips, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            private static final long serialVersionUID = 1L;

            /** 높이는 내용만큼, 단 MAX_CHIP_ROWS줄까지. 폭은 창이 정한다(작게 보고해 창 폭을 늘리지 않게). */
            @Override
            public Dimension getPreferredSize() {
                Insets in = getInsets();
                int rowHeight = new JButton("가").getPreferredSize().height + 4;
                int content = chips.getPreferredSize().height;
                int height = Math.min(content, rowHeight * MAX_CHIP_ROWS + 4);
                return new Dimension(100, height + in.top + in.bottom);
            }
        };
        chipsScroll.getVerticalScrollBar().setUnitIncrement(12);
        // 창 폭이 바뀌면 줄 수가 바뀌므로 높이를 다시 계산한다
        chipsScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
                    chips.revalidate();
                    top.revalidate();
                });
            }
        });
        JPanel labelBox = new JPanel(new BorderLayout());
        labelBox.add(new JLabel("받는 사람"), BorderLayout.NORTH); // 여러 줄이어도 글자는 맨 위에
        JPanel pickBox = new JPanel(new BorderLayout());
        pickBox.add(pickButton, BorderLayout.NORTH);
        top.add(labelBox, BorderLayout.WEST);
        top.add(chipsScroll, BorderLayout.CENTER);
        top.add(pickBox, BorderLayout.EAST);
        pickButton.addActionListener(e -> pickReceivers());

        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setMargin(new Insets(6, 6, 6, 6));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(sendButton);
        sendButton.addActionListener(e -> send());

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(body), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(520, 420);
        setMinimumSize(new Dimension(380, 300)); // 이보다 좁으면 이름 단추 하나도 다 보이지 않는다
        setLocationByPlatform(true);
        updateChips();
    }

    /** 받는 사람 칸: "이영희 ×" 단추들. ×를 누르면 뺀다. */
    private void updateChips() {
        chips.removeAll();
        if (receivers.isEmpty()) {
            JLabel none = new JLabel("(없음 — [받는 사람 선택]을 누르세요)");
            none.setEnabled(false);
            chips.add(none);
        }
        for (String id : receivers) {
            JButton chip = new JButton(controller.nameOf(id) + " ×");
            chip.setMargin(new Insets(1, 6, 1, 6));
            chip.setToolTipText(controller.nameWithId(id) + " 빼기");
            chip.addActionListener(e -> {
                receivers.remove(id);
                updateChips();
            });
            chips.add(chip);
        }
        chips.revalidate();
        chips.repaint();
        getContentPane().revalidate(); // 줄 수가 바뀌면 받는 사람 칸 높이도 바뀐다
    }

    /**
     * 받는 사람 단추 패널. 스크롤 창 안에서 폭은 창에 맞추고(가로 스크롤 없음) 높이만 늘어난다.
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

    /**
     * [받는 사람 선택]: 메인 창과 같은 체크박스 조직도 창 (U57). 지금 받는 사람은 체크된 상태로 열린다.
     * 조직도를 못 받았으면 등록 순서 목록(임시 표시). [확인]하면 체크된 사람이 받는 사람이 된다.
     */
    private void pickReceivers() {
        JDialog dialog = new JDialog(this, "받는 사람 선택", true);
        List<UserEntry> users = controller.users();
        OrgCheckTree tree = new OrgCheckTree();
        OrgTree org = controller.orgTree();
        if (org != null) {
            tree.showOrg(org, users);
        } else {
            tree.showFlat(users);
        }
        tree.setChecked(receivers);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createTitledBorder(org != null ? "조직도" : "조직도 (임시 표시)"));
        JButton ok = new JButton("확인");
        JButton cancel = new JButton("취소");
        ok.addActionListener(e -> {
            List<String> checked = tree.checkedIds();
            Set<String> chosen = new LinkedHashSet<>();
            for (String id : receivers) { // 이미 고른 순서를 유지하고 (목록에 없는 아이디는 그대로 둔다)
                if (checked.contains(id) || indexOf(users, id) < 0) {
                    chosen.add(id);
                }
            }
            chosen.addAll(checked); // 새로 고른 사람은 조직도 순서로 뒤에
            receivers.clear();
            receivers.addAll(chosen);
            updateChips();
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
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(320, 460);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private static int indexOf(List<UserEntry> users, String id) {
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void send() {
        List<String> to = new ArrayList<>(receivers);
        String text = body.getText();
        String problem = ClientController.checkDraft(to, text); // 서버에 보내기 전에 먼저 막는다 (U5)
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "보낼 수 없음", JOptionPane.WARNING_MESSAGE);
            return;
        }
        setSending(true);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return controller.sendMessage(messageId, to, text); // 성공이면 null, 실패면 이유
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
        pickButton.setEnabled(!sending);
        body.setEditable(!sending);
        sendButton.setText(sending ? "보내는 중…" : "보내기");
        for (java.awt.Component c : chips.getComponents()) {
            c.setEnabled(!sending);
        }
    }
}
