package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import coolmsg.client.ClientController;
import coolmsg.client.model.MailItem;
import coolmsg.common.model.MessageData;

/**
 * 메시지 보기 창 (설계서 4.3). 구현 4단계: 보낸 사람·받는 사람·시각·본문, [삭제] [닫기].
 *
 * 본문은 HTML(U37)이라 JEditorPane으로 보여 준다. 편집할 수 없게 한다.
 * [회신]·[전체회신]·[전달]은 7단계, 보낸 메시지의 읽음 확인은 5단계.
 */
public class MessageViewFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    public MessageViewFrame(ClientController controller, MailItem item, boolean inbox) {
        super(inbox ? "받은 메시지" : "보낸 메시지");
        MessageData d = item.getData();

        JPanel info = new JPanel(new GridLayout(0, 1, 0, 2));
        info.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        info.add(new JLabel("보낸 사람: " + controller.nameWithId(d.getSender())));
        info.add(new JLabel("받는 사람: " + controller.namesWithIds(d.getReceivers())));
        if (!d.getCc().isEmpty()) {
            info.add(new JLabel("참조: " + controller.namesWithIds(d.getCc())));
        }
        info.add(new JLabel("보낸 시각: " + item.sentAtText()));

        JEditorPane body = new JEditorPane("text/html", "<html><body>" + d.getBody() + "</body></html>");
        body.setEditable(false);
        body.setCaretPosition(0);

        JButton delete = new JButton("삭제");
        JButton close = new JButton("닫기");
        delete.addActionListener(e -> {
            int answer = JOptionPane.showConfirmDialog(this,
                    "이 메시지를 지울까요?\n내 PC의 보관함에서만 지워지며, 되돌릴 수 없습니다.",
                    "메시지 삭제", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                controller.deleteMessage(item.getMessageId(), inbox);
                dispose();
            }
        });
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(delete);
        buttons.add(close);

        setLayout(new BorderLayout());
        add(info, BorderLayout.NORTH);
        add(new JScrollPane(body), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(520, 420);
        setLocationByPlatform(true);
    }
}
