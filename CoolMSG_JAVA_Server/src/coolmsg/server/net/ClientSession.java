package coolmsg.server.net;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import coolmsg.common.FormatException;
import coolmsg.common.ProtocolInfo;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketReader;
import coolmsg.common.net.PacketType;
import coolmsg.server.ServerEventListener;

/**
 * 클라이언트 연결 하나 = 받기 스레드 + 보내기 대기열(SendQueue) + 로그인한 아이디 (설계서 3.1).
 *
 * 받기 스레드: 패킷을 읽어 RequestDispatcher에 넘기고, 돌아온 응답을 보내기 대기열에 넣는다.
 * 닫히는 경우:
 *   - 상대가 연결을 닫음(EOFException), 로그아웃
 *   - 일정 시간(기본 90초) 동안 패킷이 없음(SocketTimeoutException) — 하트비트가 끊긴 경우
 *   - 패킷 형식 오류(FormatException) — 스트림 위치가 어긋났으므로 더 읽을 수 없음
 *   - 보내기 대기열 가득, 통신 오류
 *   - 서버가 끊음(kick): 다른 곳에서 로그인, 계정 삭제, 서버 중지 — BYE로 이유를 먼저 알린다(U30)
 * close()는 여러 스레드에서 동시에 불려도 한 번만 실행된다(AtomicBoolean).
 */
public class ClientSession {

    public static final int DEFAULT_IDLE_TIMEOUT_MILLIS = ProtocolInfo.IDLE_TIMEOUT_MILLIS;

    /** kick 때 BYE를 보내고 연결을 닫기까지 기다리는 최대 시간. 클라이언트가 읽지 않아도 이 시간 뒤에는 닫는다. */
    static final int KICK_FLUSH_MILLIS = 2000;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final long id = SEQUENCE.incrementAndGet();
    private final Socket socket;
    private final String remoteAddress;
    private final PacketReader reader;
    private final SendQueue sendQueue;
    private final RequestDispatcher dispatcher;
    private final SessionRegistry registry;
    private final ServerEventListener events;
    private final Thread readThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean kicking = new AtomicBoolean(false);

    /** 로그인한 아이디. 로그인 전에는 null. SessionRegistry가 잠금 안에서 정한다. */
    private volatile String userId;

    public ClientSession(Socket socket, int idleTimeoutMillis, int queueCapacity, RequestDispatcher dispatcher,
            SessionRegistry registry, ServerEventListener events) throws IOException {
        this.socket = socket;
        this.remoteAddress = socket.getInetAddress().getHostAddress();
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.events = events;
        socket.setSoTimeout(idleTimeoutMillis); // 이 시간 동안 읽을 것이 없으면 SocketTimeoutException
        socket.setTcpNoDelay(true);             // 작은 패킷을 모아 보내느라 늦어지지 않게
        this.reader = new PacketReader(socket.getInputStream());
        this.sendQueue = new SendQueue(socket.getOutputStream(), queueCapacity, "send-" + id, this::close);
        this.readThread = new Thread(this::readLoop, "read-" + id);
        this.readThread.setDaemon(true);
    }

    /** 세션 목록에 등록하고 받기·보내기 스레드를 시작한다. */
    public void start() {
        registry.add(this);
        events.onLog("연결 열림 #" + id + " (" + remoteAddress + ")");
        sendQueue.start();
        readThread.start();
    }

    private void readLoop() {
        while (!closed.get()) {
            Packet request;
            try {
                request = reader.read();
            } catch (EOFException e) {
                close(CloseReason.REMOTE_CLOSED);
                return;
            } catch (SocketTimeoutException e) {
                close(CloseReason.IDLE_TIMEOUT);
                return;
            } catch (FormatException e) {
                events.onLog("형식 오류 #" + id + ": " + e.getMessage());
                close(CloseReason.FORMAT_ERROR);
                return;
            } catch (IOException e) {
                close(CloseReason.IO_ERROR); // 이미 닫혔으면 close()가 아무것도 하지 않는다
                return;
            }
            Packet reply = dispatcher.dispatch(this, request);
            if (reply != null) {
                send(reply);
            }
        }
    }

    /** 이 클라이언트에게 패킷을 보낸다(대기열에 넣고 바로 돌아옴). */
    public boolean send(Packet packet) {
        return sendQueue.offer(packet);
    }

    /**
     * 서버가 이 연결을 끊는다: BYE(이유)를 보내고, 다 보내면(최대 2초) 연결을 닫는다 (U30).
     * 부른 스레드는 기다리지 않는다(닫기는 별도 스레드에서). 여러 번 불러도 한 번만 실행된다.
     */
    public void kick(String reasonForUser, CloseReason closeReason) {
        if (closed.get() || !kicking.compareAndSet(false, true)) {
            return;
        }
        sendQueue.offer(new Packet(PacketType.BYE).put(HeaderKeys.REASON, reasonForUser));
        sendQueue.finish();
        Thread closer = new Thread(() -> {
            sendQueue.awaitFinished(KICK_FLUSH_MILLIS);
            close(closeReason);
        }, "kick-" + id);
        closer.setDaemon(true);
        closer.start();
    }

    /** 연결을 닫는다. 처음 한 번만 실행된다. */
    public void close(CloseReason reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sendQueue.close();
        try {
            socket.close(); // 받기 스레드의 read()와 보내기 스레드의 write()가 예외로 빠져나온다
        } catch (IOException ignored) {
            // 이미 닫힌 경우
        }
        String who = (userId == null) ? "" : " " + userId;
        registry.remove(this); // 로그인해 있었다면 "지금 이 연결일 때만" 아이디 목록에서도 뺀다 (C3)
        events.onLog("연결 닫힘 #" + id + who + " (" + remoteAddress + "): " + reason.getDescription());
    }

    /** 보내기 대기열에 쌓인 패킷 수와 최대치 (밀린 메시지 속도 조절, U36). */
    int queuedCount() {
        return sendQueue.size();
    }

    int queueCapacity() {
        return sendQueue.getCapacity();
    }

    /** SessionRegistry가 로그인 처리 잠금 안에서 부른다. */
    void setUserId(String userId) {
        this.userId = userId;
    }

    public long getId() {
        return id;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isLoggedIn() {
        return userId != null;
    }

    public boolean isClosed() {
        return closed.get();
    }
}
