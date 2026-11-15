package coolmsg.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import coolmsg.server.config.AdminConfig;
import coolmsg.server.config.ServerConfig;
import coolmsg.server.config.UserDirectory;

/**
 * 서버의 공유 객체를 딱 하나씩 만들어 들고 있는 곳 (설계서 3.1).
 *
 * 화면(ui)과 나중에 만들 통신(net)·서비스(service)는 각자 객체를 만들지 않고 여기서 받아 쓴다.
 * 그래서 같은 정보의 사본이 여러 개 생기지 않고(B4), 누가 무엇을 쓰는지 이 클래스만 보면 안다.
 *
 * 구현 1단계: 설정(ServerConfig)과 사용자 목록(UserDirectory)만 있다.
 * 다음 단계에서 세션 목록, 저장소, 서비스가 여기에 추가된다.
 */
public class ServerContext {

    private final Path configDir;
    private final AdminConfig adminConfig;
    private final ServerConfig serverConfig;
    private final UserDirectory userDirectory;
    private final List<String> startupWarnings = new ArrayList<>();

    private ServerContext(Path configDir, AdminConfig adminConfig, ServerConfig serverConfig,
            UserDirectory userDirectory) {
        this.configDir = configDir;
        this.adminConfig = adminConfig;
        this.serverConfig = serverConfig;
        this.userDirectory = userDirectory;
    }

    /**
     * config 폴더의 설정 파일들을 읽어 공유 객체를 만든다.
     *
     * @throws IOException users.txt가 있는데 읽지 못한 경우. 빈 목록으로 시작해 덮어쓰지 않도록
     *                     서버 시작을 중단해야 한다 (설계서 5장 U22)
     */
    public static ServerContext load(Path configDir, AdminConfig adminConfig) throws IOException {
        ServerConfig serverConfig = ServerConfig.load(configDir.resolve("server.txt"));
        UserDirectory userDirectory = new UserDirectory(configDir.resolve("users.txt"));
        userDirectory.load();

        ServerContext context = new ServerContext(configDir, adminConfig, serverConfig, userDirectory);
        for (String w : serverConfig.getWarnings()) {
            context.startupWarnings.add("[server.txt] " + w);
        }
        for (String w : userDirectory.getWarnings()) {
            context.startupWarnings.add("[users.txt] " + w);
        }
        return context;
    }

    public Path getConfigDir() {
        return configDir;
    }

    public AdminConfig getAdminConfig() {
        return adminConfig;
    }

    public ServerConfig getServerConfig() {
        return serverConfig;
    }

    public UserDirectory getUserDirectory() {
        return userDirectory;
    }

    /** 서버를 켤 때 생긴 경고 (관리 화면 로그에 표시). */
    public List<String> getStartupWarnings() {
        return List.copyOf(startupWarnings);
    }
}
