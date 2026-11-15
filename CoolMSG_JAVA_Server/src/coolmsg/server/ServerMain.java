package coolmsg.server;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import coolmsg.server.config.AdminConfig;
import coolmsg.server.ui.AdminLoginDialog;
import coolmsg.server.ui.ServerFrame;

/**
 * 서버 시작점.  설계서 3.1 시작 순서
 *
 * 1. 관리자 확인 (처음 실행이면 관리자 비밀번호 설정)
 * 2. ServerContext 생성 (server.txt, users.txt 읽기)
 * 3. 서버 관리 화면(ServerFrame) 표시
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

        ServerContext context;
        try {
            context = ServerContext.load(CONFIG_DIR, admin);
        } catch (IOException e) {
            // users.txt가 있는데 읽지 못함 → 빈 목록으로 시작하면 덮어써질 수 있으므로 중단 (설계서 5장 U22)
            JOptionPane.showMessageDialog(null,
                    "사용자 목록 파일을 읽지 못해 서버를 시작할 수 없습니다.\n"
                            + "파일: " + CONFIG_DIR.resolve("users.txt").toAbsolutePath() + "\n"
                            + "원인: " + e + "\n\n"
                            + "파일이 UTF-8로 저장되어 있는지, 다른 프로그램이 열고 있지 않은지 확인하세요.",
                    "CoolMsg 서버", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }

        ServerFrame frame = new ServerFrame(context);
        frame.setVisible(true);
    }
}
