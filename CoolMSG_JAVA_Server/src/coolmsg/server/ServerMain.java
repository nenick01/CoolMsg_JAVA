package coolmsg.server;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import coolmsg.server.config.AdminConfig;
import coolmsg.server.config.UserStore;
import coolmsg.server.ui.AdminLoginDialog;
import coolmsg.server.ui.ServerFrame;

/**
 * 서버 시작점.
 *
 * 1. 관리자 확인 (처음 실행이면 관리자 비밀번호 설정)
 * 2. 서버 관리 화면(ServerFrame) 표시
 *
 * 작업 폴더는 CoolMSG_JAVA_Server 프로젝트 폴더여야 config/ 경로를 찾는다.
 * (Eclipse 기본값, VS Code는 launch.json의 cwd 설정)
 */
public class ServerMain {

    private static final Path CONFIG_DIR = Paths.get("config");

    public static void main(String[] args) {
        // Swing 화면은 Swing 전용 스레드(이벤트 디스패치 스레드)에서 만들어야 한다.
        SwingUtilities.invokeLater(ServerMain::start);
    }

    private static void start() {
        try {
            // Windows에서는 Windows 모양의 버튼·창을 쓴다.
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 실패하면 Java 기본 모양으로 그대로 진행한다.
        }

        AdminConfig admin = new AdminConfig(CONFIG_DIR.resolve("admin.txt"));
        if (!AdminLoginDialog.authenticate(admin)) {
            System.exit(0);
            return;
        }

        UserStore userStore = new UserStore(CONFIG_DIR.resolve("users.txt"));
        ServerFrame frame = new ServerFrame(userStore);
        frame.setVisible(true);
    }
}
