package coolmsg.server.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;
import coolmsg.common.net.PacketWriter;
import coolmsg.server.ServerEventListener;

/**
 * 접속 받기 (설계서 3.1). [서버 시작]/[서버 중지].
 *
 * - 접속 받기 전용 스레드 하나가 accept()로 새 연결을 기다리고, 연결마다 ClientSession을 만든다.
 * - 동시 접속이 maxConnections(기본 400, U28)를 넘으면 ERROR를 보내고 바로 끊는다.
 * - 중지하면 접속 받기를 멈추고 모든 연결을 끊는다(U8). 같은 포트로 바로 다시 시작할 수 있다.
 * - 모든 네트워크 카드(0.0.0.0)에서 접속을 받는다. 같은 PC는 localhost, 다른 PC는 이 PC의 IP로 접속한다.
 * - 저장소(StorageHooks): 시작 때 포트를 연 뒤·접속을 받기 전에 열고, 중지 때 모든 연결이 닫힌 뒤 닫는다(3.8.6).
 */
public class ServerListener {

    public static final int DEFAULT_MAX_CONNECTIONS = 400;
    public static final String TOO_MANY_MESSAGE = "접속자가 너무 많습니다. 관리자에게 문의해주세요";

    private final int port;
    private final int idleTimeoutMillis;
    private final int queueCapacity;
    private final int maxConnections;
    private final RequestDispatcher dispatcher;
    private final SessionRegistry registry;
    private final ServerEventListener events;

    private ServerSocket serverSocket;
    private volatile boolean running;
    private StorageHooks storage = StorageHooks.NONE;
    /** 중지 뒤 "연결이 다 닫히길 기다렸다가 저장소 닫기"를 하는 스레드. 없으면 null. */
    private Thread stopper;

    public ServerListener(int port, RequestDispatcher dispatcher, SessionRegistry registry, ServerEventListener events) {
        this(port, dispatcher, registry, events, ClientSession.DEFAULT_IDLE_TIMEOUT_MILLIS,
                SendQueue.DEFAULT_CAPACITY, DEFAULT_MAX_CONNECTIONS);
    }

    /** 시간·한도를 바꿀 수 있는 생성자 (테스트에서 짧은 시간 제한을 쓰기 위해). */
    public ServerListener(int port, RequestDispatcher dispatcher, SessionRegistry registry, ServerEventListener events,
            int idleTimeoutMillis, int queueCapacity, int maxConnections) {
        this.port = port;
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.events = events;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.queueCapacity = queueCapacity;
        this.maxConnections = maxConnections;
    }

    /**
     * 접속 받기를 시작한다. 이미 실행 중이면 아무것도 하지 않는다.
     *
     * @throws IOException 포트를 열지 못함 (이미 사용 중이면 java.net.BindException)
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        awaitStopped(0); // 직전 중지의 저장소 닫기가 끝난 뒤에 연다
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true); // 중지 직후 같은 포트로 다시 시작할 수 있게
        try {
            ss.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            ss.close();
            throw e;
        }
        try {
            storage.open();
        } catch (IOException e) {
            ss.close();
            throw e;
        }
        serverSocket = ss;
        running = true;
        Thread acceptThread = new Thread(() -> acceptLoop(ss), "accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        events.onLog("서버 시작 (포트 " + ss.getLocalPort() + ")");
        events.onServerStateChanged(true);
    }

    public static final String SERVER_STOP_MESSAGE = "서버가 중지되었습니다.";

    /**
     * 접속 받기를 멈추고, 모든 연결에 BYE("서버가 중지되었습니다")를 보낸 뒤 끊는다(U8, U30).
     * 끊기는 별도 스레드에서 진행되므로 바로 돌아온다. 실행 중이 아니면 아무것도 하지 않는다.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            serverSocket.close(); // accept()가 예외로 빠져나온다
        } catch (IOException ignored) {
            // 이미 닫힘
        }
        registry.kickAll(SERVER_STOP_MESSAGE, CloseReason.SERVER_STOP);
        events.onLog("서버 중지");
        events.onServerStateChanged(false);
        StorageHooks s = storage;
        stopper = new Thread(() -> {
            awaitAllClosed(ClientSession.KICK_FLUSH_MILLIS + 1000);
            s.close();
        }, "stopper");
        stopper.setDaemon(true);
        stopper.start();
    }

    /** 저장소를 연결·해제할 대상. 시작 전에 한 번 정한다. */
    public synchronized void setStorageHooks(StorageHooks hooks) {
        this.storage = (hooks == null) ? StorageHooks.NONE : hooks;
    }

    /**
     * 중지 뒤 저장소 닫기(정상 종료 표시)가 끝나기를 기다린다. 프로그램을 끝내기 직전에 부른다.
     *
     * @param millis 최대 대기 시간. 0이면 끝날 때까지
     */
    public void awaitStopped(long millis) {
        Thread t;
        synchronized (this) {
            t = stopper;
        }
        if (t == null) {
            return;
        }
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 모든 연결이 닫히기를 최대 millis만큼 기다린다.
     * 프로그램을 끝내기 직전에 불러, BYE가 실제로 전달될 시간을 준다.
     */
    public void awaitAllClosed(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (registry.size() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** 실제로 열린 포트 (테스트에서 포트 0으로 시작한 경우 확인용). 실행 중이 아니면 -1. */
    public synchronized int getLocalPort() {
        return running ? serverSocket.getLocalPort() : -1;
    }

    private void acceptLoop(ServerSocket ss) {
        while (running) {
            Socket socket;
            try {
                socket = ss.accept();
            } catch (IOException e) {
                if (running) {
                    events.onLog("접속 받기 오류: " + e.getMessage());
                }
                return; // 중지되어 서버 소켓이 닫힌 경우
            }
            if (registry.size() >= maxConnections) {
                reject(socket);
                continue;
            }
            try {
                new ClientSession(socket, idleTimeoutMillis, queueCapacity, dispatcher, registry, events).start();
            } catch (IOException e) {
                events.onLog("연결 준비 실패: " + e.getMessage());
                closeQuietly(socket);
            }
        }
    }

    /** 접속 수 초과: ERROR를 보내고 바로 끊는다 (U28). */
    private void reject(Socket socket) {
        events.onLog("접속 거부 (" + socket.getInetAddress().getHostAddress() + "): 동시 접속 " + maxConnections + "개 초과");
        try {
            new PacketWriter(socket.getOutputStream())
                    .write(new Packet(PacketType.ERROR).put(HeaderKeys.REASON, TOO_MANY_MESSAGE));
        } catch (IOException ignored) {
            // 알리지 못해도 끊는다
        } finally {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 무시
        }
    }
}
