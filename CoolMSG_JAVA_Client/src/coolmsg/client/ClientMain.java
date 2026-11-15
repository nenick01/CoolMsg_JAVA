package coolmsg.client;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import coolmsg.client.ui.LoginFrame;

/**
 * 클라이언트 시작점 (설계서 4.1).
 *
 * 구현 2단계: 로그인 화면과 서버 연결 테스트. 로그인은 3단계에서 연결한다.
 */
public class ClientMain {

    public static void main(String[] args) {
        // Swing 화면은 Swing 전용 스레드(이벤트 디스패치 스레드)에서 만들어야 한다.
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // 실패하면 Java 기본 모양으로 진행
            }
            new LoginFrame().setVisible(true);
        });
    }
}
