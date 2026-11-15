package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;

/**
 * 화면 오른쪽 아래의 작은 알림 창 (설계서 4.3, 5장 U41·U51·U52).
 *
 * - 5초 뒤 저절로 닫힌다. 누르면 onClick을 실행하고 닫힌다.
 * - 포커스를 가져가지 않는다(입력 중인 글이 끊기지 않게): setFocusableWindowState(false).
 * - 종류(Kind)마다 하나씩. 같은 종류의 새 알림은 그 창을 바꾼다.
 * - 종류가 다르면 위아래로 쌓는다(U52): 새 메시지가 맨 아래, 읽음 알림이 그 위. 하나가 닫히면 남은 것이 내려온다.
 * Swing 스레드에서만 부른다.
 */
public final class Toast {

    public static final int SHOW_MILLIS = 5000;
    private static final int MARGIN = 16;
    private static final int GAP = 8;

    /** 알림 종류. 선언 순서가 아래부터 쌓이는 순서다. */
    public enum Kind {
        NEW_MESSAGE(new Color(0xFF, 0xFD, 0xE7)),
        READ_RECEIPT(new Color(0xEA, 0xF4, 0xFF));

        private final Color background;

        Kind(Color background) {
            this.background = background;
        }
    }

    private static final Map<Kind, JWindow> SHOWING = new EnumMap<>(Kind.class);

    private Toast() {
    }

    public static void show(Kind kind, String title, String detail, Runnable onClick) {
        close(kind);
        JWindow w = new JWindow();
        w.setFocusableWindowState(false); // 포커스를 빼앗지 않음
        w.setAutoRequestFocus(false);
        w.setAlwaysOnTop(true);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBackground(kind.background);
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
                close(kind);
                onClick.run();
            }
        });
        w.setContentPane(panel);
        w.pack();
        w.setSize(Math.max(280, w.getWidth()), w.getHeight());
        SHOWING.put(kind, w);
        arrange();
        w.setVisible(true);

        Timer timer = new Timer(SHOW_MILLIS, e -> {
            if (SHOWING.get(kind) == w) {
                close(kind);
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    /** 떠 있는 창들을 오른쪽 아래부터 위로 쌓는다 (작업 표시줄을 뺀 화면 영역 기준). */
    private static void arrange() {
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int bottom = screen.y + screen.height - MARGIN;
        for (Kind k : Kind.values()) {
            JWindow w = SHOWING.get(k);
            if (w != null) {
                w.setLocation(screen.x + screen.width - w.getWidth() - MARGIN, bottom - w.getHeight());
                bottom -= w.getHeight() + GAP;
            }
        }
    }

    /** 그 종류의 알림을 닫고, 남은 것을 아래로 내린다. */
    public static void close(Kind kind) {
        JWindow w = SHOWING.remove(kind);
        if (w != null) {
            w.dispose();
            arrange();
        }
    }

    /** 떠 있는 알림을 모두 닫는다 (로그아웃 때 등). */
    public static void close() {
        for (Kind k : Kind.values()) {
            close(k);
        }
    }
}
