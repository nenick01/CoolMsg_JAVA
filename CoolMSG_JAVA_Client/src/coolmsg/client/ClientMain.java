package coolmsg.client;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import coolmsg.client.store.ClientPaths;

/**
 * 클라이언트 시작점 (설계서 4.1).
 *
 * 저장 위치는 사용자 홈 폴더의 .coolmsg/ (U34). 화면 전환과 종료는 ClientController가 맡는다.
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
            new ClientController(ClientPaths.inUserHome()).start();
        });
    }
}
