package coolmsg.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import coolmsg.server.config.AdminConfig;
import coolmsg.server.config.ServerConfig;
import coolmsg.server.config.UserDirectory;
import coolmsg.server.net.RequestDispatcher;
import coolmsg.server.net.ServerListener;
import coolmsg.server.net.SessionRegistry;
import coolmsg.server.net.StorageHooks;
import coolmsg.server.service.LoginService;
import coolmsg.server.service.MessageService;
import coolmsg.server.store.MessageStore;
import coolmsg.server.store.PendingIndex;

/**
 * 서버의 공유 객체를 딱 하나씩 만들어 연결하는 곳 (설계서 3.1).
 *
 * 화면(ui)·통신(net)·서비스(service)는 각자 객체를 만들지 않고 여기서 받아 쓴다.
 * 그래서 같은 정보의 사본이 여러 개 생기지 않고(B4), 누가 무엇을 쓰는지 이 클래스만 보면 안다.
 *
 * 구현 4단계까지: 설정, 사용자 목록, 사건 중계기, 세션 목록, 로그인 서비스(PING·LOGIN),
 * 메시지 저장소·미전달 색인·메시지 서비스(SEND·ACK·밀린 메시지), 패킷 분배기, 접속 받기.
 */
public class ServerContext {

    /** 운영 데이터가 없을 때 복사해 쓸 예시가 있는 폴더 (U24). */
    public static final String SAMPLE_DIR = "sample";

    private final Path configDir;
    private final Path dataDir;
    private final AdminConfig adminConfig;
    private final ServerConfig serverConfig;
    private final UserDirectory userDirectory;
    private final ServerEvents events = new ServerEvents();
    private final SessionRegistry sessionRegistry = new SessionRegistry(events);
    private final LoginService loginService;
    private final MessageStore messageStore;
    private final PendingIndex pendingIndex;
    private final MessageService messageService;
    private final RequestDispatcher dispatcher;
    private final ServerListener serverListener;
    private final List<String> startupWarnings = new ArrayList<>();

    private ServerContext(Path configDir, Path dataDir, AdminConfig adminConfig, ServerConfig serverConfig,
            UserDirectory userDirectory) {
        this.configDir = configDir;
        this.dataDir = dataDir;
        this.adminConfig = adminConfig;
        this.serverConfig = serverConfig;
        this.userDirectory = userDirectory;
        this.loginService = new LoginService(serverConfig, userDirectory);
        this.messageStore = new MessageStore(dataDir.resolve("messages"));
        this.pendingIndex = new PendingIndex(dataDir.resolve("pending"));
        this.messageService = new MessageService(messageStore, pendingIndex, userDirectory, sessionRegistry, events);
        this.dispatcher = new RequestDispatcher(loginService, messageService, sessionRegistry, events);
        this.serverListener = new ServerListener(serverConfig.getPort(), dispatcher, sessionRegistry, events);
        this.serverListener.setStorageHooks(new StorageHooks() {
            @Override
            public void open() throws IOException {
                messageService.openStorage();
            }

            @Override
            public void close() {
                messageService.closeStorage();
            }
        });
    }

    /**
     * config 폴더의 설정 파일들을 읽어 공유 객체를 만든다. 메시지는 dataDir(data/) 아래에 저장한다.
     *
     * @throws IOException users.txt를 준비하거나 읽지 못한 경우. 빈 목록으로 시작해 덮어쓰지 않도록
     *                     서버 시작을 중단해야 한다 (설계서 5장 U22, U24)
     */
    public static ServerContext load(Path configDir, Path dataDir, AdminConfig adminConfig) throws IOException {
        List<String> copied = new ArrayList<>();
        copyFromSampleIfMissing(configDir, "users.txt", copied);
        copyFromSampleIfMissing(configDir, "org.txt", copied);

        ServerConfig serverConfig = ServerConfig.load(configDir.resolve("server.txt"));
        UserDirectory userDirectory = new UserDirectory(configDir.resolve("users.txt"));
        userDirectory.load();

        ServerContext context = new ServerContext(configDir, dataDir, adminConfig, serverConfig, userDirectory);
        context.startupWarnings.addAll(copied);
        for (String w : serverConfig.getWarnings()) {
            context.startupWarnings.add("[server.txt] " + w);
        }
        for (String w : userDirectory.getWarnings()) {
            context.startupWarnings.add("[users.txt] " + w);
        }
        return context;
    }

    /** 운영 파일이 없고 예시가 있으면 예시를 복사한다 (U24). 복사 실패는 예외로 알린다. */
    private static void copyFromSampleIfMissing(Path configDir, String name, List<String> notes) throws IOException {
        Path target = configDir.resolve(name);
        Path sample = configDir.resolve(SAMPLE_DIR).resolve(name);
        if (!Files.exists(target) && Files.exists(sample)) {
            Files.copy(sample, target);
            notes.add("[" + name + "] 운영 파일이 없어 예시(" + SAMPLE_DIR + "/" + name + ")를 복사했습니다.");
        }
    }

    public Path getConfigDir() {
        return configDir;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public MessageService getMessageService() {
        return messageService;
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

    /** 서버 사건 중계기. 화면은 getEvents().addTarget(화면)으로 연결한다. */
    public ServerEvents getEvents() {
        return events;
    }

    public SessionRegistry getSessionRegistry() {
        return sessionRegistry;
    }

    public ServerListener getServerListener() {
        return serverListener;
    }

    /** 서버를 켤 때 생긴 경고 (관리 화면 로그에 표시). */
    public List<String> getStartupWarnings() {
        return List.copyOf(startupWarnings);
    }
}
