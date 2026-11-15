package coolmsg.server.net;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketType;
import coolmsg.common.net.PacketWriter;

/**
 * 연결 하나의 보내기 대기열 + 보내기 전용 스레드 (설계서 3.1, 결정 9, 동시성 규칙 C6).
 *
 * - 다른 스레드는 offer()로 대기열에 넣고 바로 돌아간다. 실제로 소켓에 쓰는 것은 이 객체의 스레드 하나뿐이다.
 *   그래서 여러 스레드가 동시에 보내도 패킷 바이트가 섞이지 않고, 느린 수신자 때문에 다른 스레드가 멈추지 않는다.
 * - 대기열은 최대 capacity개(기본 500, U28). 가득 차면 그 클라이언트가 너무 느린 것으로 보고 연결을 끊게 한다.
 * - 쓰기 실패나 대기열 가득은 onProblem으로 알린다(ClientSession이 연결을 닫는다).
 */
public class SendQueue {

    public static final int DEFAULT_CAPACITY = 500;

    /** 스레드를 멈추게 하는 표시용 패킷 (내용은 보내지 않는다). */
    private static final Packet STOP = new Packet(PacketType.ERROR);

    private final BlockingQueue<Packet> queue;
    private final int capacity;
    private final PacketWriter writer;
    private final Consumer<CloseReason> onProblem;
    private final Thread thread;
    private volatile boolean closed;

    public SendQueue(OutputStream out, int capacity, String threadName, Consumer<CloseReason> onProblem) {
        // 용량 + 1: 가득 찬 상태에서도 STOP 표시는 넣을 수 있게
        this.queue = new LinkedBlockingQueue<>(capacity + 1);
        this.writer = new PacketWriter(out);
        this.onProblem = onProblem;
        this.thread = new Thread(this::run, threadName);
        this.thread.setDaemon(true); // 이 스레드 때문에 프로그램이 안 꺼지는 일이 없게
        this.capacity = capacity;
    }

    public void start() {
        thread.start();
    }

    /**
     * 보낼 패킷을 대기열에 넣는다. 바로 돌아온다.
     *
     * @return 넣었으면 true. 이미 닫혔거나 대기열이 가득 차면 false (가득 차면 onProblem(QUEUE_FULL)도 부른다)
     */
    public boolean offer(Packet packet) {
        if (closed) {
            return false;
        }
        if (queue.size() >= capacity || !queue.offer(packet)) {
            onProblem.accept(CloseReason.QUEUE_FULL);
            return false;
        }
        return true;
    }

    /** 대기 중인 패킷 수 (테스트·로그용). */
    public int size() {
        return queue.size();
    }

    /** 대기열을 비우고 보내기 스레드를 멈춘다. 여러 번 불러도 된다. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        queue.clear();
        queue.offer(STOP);
    }

    private void run() {
        try {
            while (true) {
                Packet packet = queue.take(); // 비어 있으면 여기서 기다린다
                if (packet == STOP) {
                    return;
                }
                writer.write(packet);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed) {
                onProblem.accept(CloseReason.IO_ERROR);
            }
        }
    }
}
