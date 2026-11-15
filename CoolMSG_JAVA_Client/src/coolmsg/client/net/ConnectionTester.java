package coolmsg.client.net;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import coolmsg.common.FormatException;
import coolmsg.common.ProtocolInfo;
import coolmsg.common.net.HeaderKeys;
import coolmsg.common.net.Packet;
import coolmsg.common.net.PacketReader;
import coolmsg.common.net.PacketType;
import coolmsg.common.net.PacketWriter;

/**
 * 서버 연결 테스트 (설계서 2.4).
 *
 * 로그인 연결과는 별개의 짧은 연결을 열어 PING을 보내고 PONG을 받으면 끊는다.
 * 연결 3초, 응답 3초 제한. 실패하면 원인(Java 예외 종류)별로 결과를 나눈다.
 * 화면이 멈추지 않도록 Swing 스레드가 아닌 곳(SwingWorker)에서 불러야 한다.
 */
public class ConnectionTester {

    /** 테스트 결과 종류. */
    public enum Outcome {
        SUCCESS("연결 성공"),
        VERSION_MISMATCH("서버와 프로그램의 버전이 다릅니다"),
        REFUSED("연결 거부: 서버가 꺼져 있거나 포트가 다릅니다"),
        TIMEOUT("시간 초과: IP가 틀렸거나 방화벽이 막고 있습니다"),
        UNKNOWN_HOST("서버 주소를 찾을 수 없습니다"),
        SERVER_ERROR("서버가 접속을 거절했습니다"),
        PROTOCOL_ERROR("CoolMsg 서버가 아닌 것 같습니다 (응답 형식이 다름)"),
        IO_ERROR("통신 오류");

        private final String description;

        Outcome(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /** 테스트 결과 (불변). */
    public static final class Result {
        private final Outcome outcome;
        private final String serverName;
        private final int version;
        private final long millis;
        private final String detail;

        Result(Outcome outcome, String serverName, int version, long millis, String detail) {
            this.outcome = outcome;
            this.serverName = serverName;
            this.version = version;
            this.millis = millis;
            this.detail = detail;
        }

        static Result fail(Outcome outcome, String detail) {
            return new Result(outcome, null, -1, -1, detail);
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public boolean isSuccess() {
            return outcome == Outcome.SUCCESS;
        }

        /** 서버 이름 (PONG을 받은 경우). */
        public String getServerName() {
            return serverName;
        }

        /** 서버 프로토콜 버전 (PONG을 받은 경우, 아니면 -1). */
        public int getVersion() {
            return version;
        }

        /** PING을 보낸 뒤 PONG을 받기까지 걸린 시간(ms). 받지 못했으면 -1. */
        public long getMillis() {
            return millis;
        }

        /** 원인에 대한 추가 설명 (예외 메시지, 서버가 보낸 이유 등). 없으면 null. */
        public String getDetail() {
            return detail;
        }
    }

    private final int timeoutMillis;

    public ConnectionTester() {
        this(ProtocolInfo.CONNECT_TEST_TIMEOUT_MILLIS);
    }

    /** 시간 제한을 바꿀 수 있는 생성자 (테스트용). 연결·응답 각각에 적용된다. */
    public ConnectionTester(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** 연결 테스트를 한다. 예외를 던지지 않고 결과로 돌려준다. */
    public Result test(ServerAddress address) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.getHost(), address.getPort()), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            PacketWriter writer = new PacketWriter(socket.getOutputStream());
            PacketReader reader = new PacketReader(socket.getInputStream());

            long start = System.nanoTime();
            writer.write(new Packet(PacketType.PING).setReqId(1));
            Packet reply = reader.read();
            long millis = (System.nanoTime() - start) / 1_000_000;

            if (reply.getType() == PacketType.ERROR) {
                return Result.fail(Outcome.SERVER_ERROR, reply.get(HeaderKeys.REASON));
            }
            if (reply.getType() != PacketType.PONG || reply.getReqId() != 1) {
                return Result.fail(Outcome.PROTOCOL_ERROR, "받은 응답: " + reply.getTypeName());
            }
            int version = reply.requireInt(HeaderKeys.VERSION);
            String serverName = reply.get(HeaderKeys.SERVER_NAME);
            Outcome outcome = (version == ProtocolInfo.VERSION) ? Outcome.SUCCESS : Outcome.VERSION_MISMATCH;
            return new Result(outcome, serverName, version, millis, null);

        } catch (UnknownHostException e) {
            return Result.fail(Outcome.UNKNOWN_HOST, e.getMessage());
        } catch (ConnectException e) {
            return Result.fail(Outcome.REFUSED, e.getMessage());
        } catch (SocketTimeoutException e) {
            return Result.fail(Outcome.TIMEOUT, e.getMessage());
        } catch (FormatException e) {
            return Result.fail(Outcome.PROTOCOL_ERROR, e.getMessage());
        } catch (EOFException e) {
            return Result.fail(Outcome.PROTOCOL_ERROR, "서버가 응답 없이 연결을 닫았습니다.");
        } catch (IOException e) {
            return Result.fail(Outcome.IO_ERROR, e.toString());
        }
    }
}
