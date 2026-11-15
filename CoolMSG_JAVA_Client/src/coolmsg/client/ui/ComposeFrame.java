package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;

import coolmsg.client.ClientController;
import coolmsg.client.model.UserEntry;

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
    private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final JTextArea body = new JTextArea();
    private final JButton sendButton = new JButton("보내기");
    private final JButton pickButton = new JButton("받는 사람 선택");

    public ComposeFrame(ClientController controller, List<String> initialReceivers) {
        super("메시지 쓰기");
        this.controller = controller;
        receivers.addAll(initialReceivers);

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(new JLabel("받는 사람"), BorderLayout.WEST);
        top.add(chips, BorderLayout.CENTER);
        top.add(pickButton, BorderLayout.EAST);
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
    }

    /** 체크 목록 창으로 받는 사람을 더하거나 뺀다. */
    private void pickReceivers() {
        JDialog dialog = new JDialog(this, "받는 사람 선택", true);
        JPanel list = new JPanel(new GridLayout(0, 1));
        List<JCheckBox> boxes = new ArrayList<>();
        List<UserEntry> users = controller.users();
        for (UserEntry u : users) {
            JCheckBox box = new JCheckBox(u.getName() + " (" + u.getId() + ")", receivers.contains(u.getId()));
            boxes.add(box);
            list.add(box);
        }
        JButton ok = new JButton("확인");
        JButton cancel = new JButton("취소");
        ok.addActionListener(e -> {
            Set<String> chosen = new LinkedHashSet<>();
            for (String id : receivers) { // 이미 고른 순서를 유지하고
                int i = indexOf(users, id);
                if (i >= 0 && boxes.get(i).isSelected()) {
                    chosen.add(id);
                }
            }
            for (int i = 0; i < users.size(); i++) { // 새로 고른 사람은 뒤에
                if (boxes.get(i).isSelected()) {
                    chosen.add(users.get(i).getId());
                }
            }
            receivers.clear();
            receivers.addAll(chosen);
            updateChips();
            dialog.dispose();
        });
        cancel.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);

        dialog.setLayout(new BorderLayout());
        dialog.add(new JScrollPane(list), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(280, 360);
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
