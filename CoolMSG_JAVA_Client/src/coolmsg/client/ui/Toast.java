package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;

/**
 * 화면 오른쪽 아래의 작은 알림 창 (설계서 4.3, 5장 U41).
 *
 * - 5초 뒤 저절로 닫힌다. 누르면 onClick을 실행하고 닫힌다.
 * - 포커스를 가져가지 않는다(입력 중인 글이 끊기지 않게): setFocusableWindowState(false).
 * - 한 번에 하나만 보인다. 새 알림이 오면 이전 것은 닫는다.
 * Swing 스레드에서만 부른다.
 */
public final class Toast {

    public static final int SHOW_MILLIS = 5000;

    private static JWindow current;

    private Toast() {
    }

    public static void show(String title, String detail, Runnable onClick) {
        close();
        JWindow w = new JWindow();
        w.setFocusableWindowState(false); // 포커스를 빼앗지 않음
        w.setAutoRequestFocus(false);
        w.setAlwaysOnTop(true);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBackground(new Color(0xFF, 0xFD, 0xE7));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x99, 0x99, 0x99)),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        JLabel head = new JLabel(title);
        head.setFont(head.getFont().deriveFont(Font.BOLD));
        panel.add(head, BorderLayout.NORTH);
        if (detail != null && !detail.isEmpty()) {
            panel.add(new JLabel(detail), BorderLayout.CENTER);
        }
        JLabel hint = new JLabel("눌러서 열기");
        hint.setForeground(Color.GRAY);
        hint.setFont(hint.getFont().deriveFont(11f));
        panel.add(hint, BorderLayout.SOUTH);
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                close();
                onClick.run();
            }
        });
        w.setContentPane(panel);
        w.pack();
        w.setSize(Math.max(260, w.getWidth()), w.getHeight());

        // 작업 표시줄을 뺀 화면 영역의 오른쪽 아래
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        w.setLocation(screen.x + screen.width - w.getWidth() - 16, screen.y + screen.height - w.getHeight() - 16);
        w.setVisible(true);
        current = w;

        Timer timer = new Timer(SHOW_MILLIS, e -> {
            if (current == w) {
                close();
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    /** 떠 있는 알림을 닫는다 (로그아웃 때 등). */
    public static void close() {
        if (current != null) {
            current.dispose();
            current = null;
        }
    }
}
