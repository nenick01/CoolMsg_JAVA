package coolmsg.client.net;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import coolmsg.common.ProtocolInfo;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketReader;
import coolmsg.common.net.PacketType;
import coolmsg.common.net.PacketWriter;

/**
 * 서버와의 연결 하나 (설계서 4.1). 서버의 ClientSession과 같은 구조: 받기 스레드 1 + 보내기 스레드 1.
 *
 * - 요청에는 reqId를 붙이고 "대기표"(pending)에 넣어 둔다. 받기 스레드가 같은 reqId의 응답을 받으면 대기표를 채운다.
 *   응답이 10초 안에 오지 않으면 그 요청은 실패한다(U10).
 * - reqId가 없는 패킷(서버가 먼저 보내는 것)은 Listener로 넘긴다. PONG(하트비트 응답)은 버린다.
 * - BYE를 받으면 이유와 함께 끊김을 알린다(U30).
 * - 서버에서 90초 동안 아무 패킷도 오지 않으면 끊긴 것으로 본다(U32).
 * - 끊김은 한 번만 알린다. 우리가 스스로 닫은 경우(로그아웃)는 알리지 않는다.
 */
public class Connection {

    /** 서버가 먼저 보내는 것과 연결 끊김을 받는 쪽 (ClientController). 받기 스레드에서 불린다. */
    public interface Listener {
        /** reqId 응답이 아닌 패킷 (MSG, PRESENCE, RECEIPT 등 — 구현 4단계부터). */
        void onPacket(Packet packet);

        /** 연결이 끊김. byServer는 서버가 BYE로 이유를 알려 준 경우. */
        void onDisconnected(String reason, boolean byServer);
    }

    public static final String LOST_MESSAGE = "서버와 연결이 끊겼습니다.";
    public static final String SILENT_MESSAGE = "서버에서 응답이 없어 연결이 끊긴 것으로 판단했습니다.";

    private static final Packet STOP = new Packet(PacketType.ERROR);

    private final Socket socket;
    private final PacketReader reader;
    private final PacketWriter writer;
    private final Listener listener;
    private final int requestTimeoutMillis;
    private final BlockingQueue<Packet> sendQueue = new LinkedBlockingQueue<>();
    private final Map<Integer, CompletableFuture<Packet>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextReqId = new AtomicInteger(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Thread sendThread;

    private Connection(Socket socket, Listener listener, int requestTimeoutMillis) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.reader = new PacketReader(socket.getInputStream());
        this.writer = new PacketWriter(socket.getOutputStream());
    }

    /** 기본 시간 제한(연결 3초, 서버 침묵 90초, 요청 10초)으로 연결한다. */
    public static Connection open(ServerAddress address, Listener listener) throws IOException {
        return open(address, listener, ProtocolInfo.CONNECT_TEST_TIMEOUT_MILLIS, ProtocolInfo.IDLE_TIMEOUT_MILLIS,
                ProtocolInfo.REQUEST_TIMEOUT_MILLIS);
    }

    /** 시간 제한을 바꿀 수 있는 연결 (테스트용). */
    public static Connection open(ServerAddress address, Listener listener, int connectTimeoutMillis,
            int idleTimeoutMillis, int requestTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address.getHost(), address.getPort()), connectTimeoutMillis);
            socket.setSoTimeout(idleTimeoutMillis); // 서버 침묵 감지 (U32)
            socket.setTcpNoDelay(true);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        Connection c = new Connection(socket, listener, requestTimeoutMillis);
        c.startThreads();
        return c;
    }

    private void startThreads() {
        Thread readThread = new Thread(this::readLoop, "client-read");
        sendThread = new Thread(this::sendLoop, "client-send");
        readThread.setDaemon(true);
        sendThread.setDaemon(true);
        readThread.start();
        sendThread.start();
    }

    /**
     * 요청을 보내고 응답을 기다릴 수 있는 Future를 돌려준다. reqId는 여기서 붙인다.
     * 응답이 시간 안에 오지 않으면 Future가 TimeoutException으로 끝난다(U10).
     */
    public CompletableFuture<Packet> request(Packet packet) {
        int reqId = nextReqId.getAndIncrement();
        packet.setReqId(reqId);
        CompletableFuture<Packet> future = new CompletableFuture<>();
        pending.put(reqId, future);
        future.orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((reply, error) -> pending.remove(reqId)); // 끝나면(성공·실패) 대기표에서 뺀다
        if (!send(packet)) {
            future.completeExceptionally(new IOException(LOST_MESSAGE));
        }
        return future;
    }

    /** 응답을 기다리지 않고 보낸다 (하트비트, 로그아웃, ACK 등). 닫혔으면 false. */
    public boolean send(Packet packet) {
        if (closed.get()) {
            return false;
        }
        return sendQueue.offer(packet);
    }

    /** 우리가 스스로 연결을 닫는다(로그아웃·종료). 끊김 알림은 보내지 않는다. 남은 패킷은 잠깐 기다려 보낸다. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sendQueue.offer(STOP);
        failPending();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 보내기 스레드가 남은 패킷을 다 보내고 끝나기를 최대 millis만큼 기다린다 (프로그램 종료 직전). */
    public void awaitClosed(long millis) {
        try {
            sendThread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================

    private void readLoop() {
        String reason = LOST_MESSAGE;
        boolean byServer = false;
        try {
            while (!closed.get()) {
                Packet p = reader.read();
                int reqId = p.getReqId();
                CompletableFuture<Packet> waiting = (reqId > 0) ? pending.remove(reqId) : null;
                if (waiting != null) {
                    waiting.complete(p); // 기다리던 요청의 응답
                } else if (p.getType() == PacketType.BYE) {
                    reason = p.get(HeaderKeys.REASON);
                    byServer = true;
                    break;
                } else if (p.getType() == PacketType.PONG || p.getType() == PacketType.ERROR) {
                    // 하트비트 응답, reqId 없는 ERROR는 로그만 (U10)
                    if (p.getType() == PacketType.ERROR) {
                        System.err.println("[서버 오류 알림] " + p.get(HeaderKeys.REASON));
                    }
                } else {
                    listener.onPacket(p);
                }
            }
        } catch (SocketTimeoutException e) {
            reason = SILENT_MESSAGE;
        } catch (EOFException e) {
            reason = LOST_MESSAGE;
        } catch (IOException e) {
            reason = LOST_MESSAGE;
        }
        shutdownAfterLoss(reason, byServer);
    }

    private void sendLoop() {
        try {
            while (true) {
                Packet p = sendQueue.take();
                if (p == STOP) {
                    break;
                }
                writer.write(p);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 받기 스레드도 곧 끊김을 알아챈다
        }
        closeSocket();
    }

    /** 받기 스레드가 끊김을 알아챘을 때. 우리가 닫은 게 아니면 한 번만 알린다. */
    private void shutdownAfterLoss(String reason, boolean byServer) {
        boolean firstClose = closed.compareAndSet(false, true);
        sendQueue.offer(STOP);
        closeSocket();
        failPending();
        if (firstClose) {
            listener.onDisconnected(reason == null ? LOST_MESSAGE : reason, byServer);
        }
    }

    private void failPending() {
        for (CompletableFuture<Packet> f : pending.values()) {
            f.completeExceptionally(new IOException(LOST_MESSAGE));
        }
        pending.clear();
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
    }
}
