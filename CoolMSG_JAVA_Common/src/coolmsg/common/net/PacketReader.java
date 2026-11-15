package coolmsg.common.net;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import coolmsg.common.FormatException;
import coolmsg.common.ProtocolInfo;
import coolmsg.common.file.HeaderFormat;

/**
 * 패킷 읽기.  설계서 2.1
 *
 * 받는 쪽은 길이를 먼저 읽고(readInt), 정확히 그만큼만 읽는다(readFully).
 * 그래서 본문에 줄바꿈이 있어도, 데이터가 바이너리여도 패킷의 경계를 정확히 안다.
 *
 * 예외:
 * - EOFException: 상대가 연결을 닫음 (패킷 시작 전이면 정상 종료로 봐도 된다)
 * - FormatException: 길이가 범위를 벗어남, 헤더 형식 오류 → 스트림 위치가 어긋났으므로 연결을 닫아야 한다
 *
 * 모르는 종류(type)의 패킷은 예외 없이 그대로 돌려준다(getType()이 null). 서버가 ERROR로 응답하기 위해서다.
 * 스레드 안전하지 않다. 한 연결에서는 받기 스레드 하나만 이 객체를 쓴다.
 */
public class PacketReader {

    private final DataInputStream in;

    public PacketReader(InputStream in) {
        this.in = new DataInputStream(new BufferedInputStream(in));
    }

    /** 일반 패킷 하나를 통째로 읽는다. */
    public Packet read() throws IOException {
        int headerLength = in.readInt(); // 연결이 닫혀 있으면 EOFException
        if (headerLength <= 0 || headerLength > ProtocolInfo.MAX_HEADER_BYTES) {
            throw new FormatException("헤더 길이가 범위를 벗어났습니다: " + headerLength);
        }
        byte[] headerBytes = new byte[headerLength];
        readFully(headerBytes);

        LinkedHashMap<String, String> header = HeaderFormat.parse(HeaderFormat.decodeUtf8(headerBytes));
        if (header.isEmpty() || !Packet.KEY_TYPE.equals(header.keySet().iterator().next())) {
            throw new FormatException("헤더의 첫 줄이 type=이 아닙니다.");
        }
        String typeName = header.remove(Packet.KEY_TYPE);
        if (typeName.isEmpty()) {
            throw new FormatException("type이 비어 있습니다.");
        }

        int dataLength = in.readInt();
        if (dataLength < 0 || dataLength > ProtocolInfo.MAX_DATA_BYTES) {
            throw new FormatException("데이터 길이가 범위를 벗어났습니다: " + dataLength);
        }
        byte[] data = new byte[dataLength];
        readFully(data);

        Packet packet = new Packet(typeName);
        for (Map.Entry<String, String> e : header.entrySet()) {
            packet.put(e.getKey(), e.getValue());
        }
        packet.setData(data);
        return packet;
    }

    /**
     * 파일 데이터를 64KB씩 디스크로 받는다 (설계서 2.12).
     * 첨부파일은 제출 범위에서 뼈대만 있으므로 아직 구현하지 않는다 (설계서 1.3).
     */
    public Packet readFileTo(Path target, ProgressListener listener) throws IOException {
        throw new UnsupportedOperationException("첨부파일 전송은 제출 후 확장 기능입니다 (설계서 1.3).");
    }

    private void readFully(byte[] buffer) throws IOException {
        try {
            in.readFully(buffer); // 정해진 바이트 수를 다 받을 때까지 기다린다
        } catch (EOFException e) {
            throw new EOFException("패킷을 읽는 도중 연결이 끊겼습니다.");
        }
    }
}
