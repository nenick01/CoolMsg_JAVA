package coolmsg.common.net;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import coolmsg.common.ProtocolInfo;
import coolmsg.common.file.HeaderFormat;

/**
 * 패킷 쓰기.  설계서 2.1
 *
 * <pre>
 * [int 헤더 길이][헤더 UTF-8 "type=...\n키=값\n..."][int 데이터 길이][데이터]
 * </pre>
 *
 * 스레드 안전하지 않다. 한 연결에서는 보내기 스레드 하나만 이 객체를 쓴다(설계서 3.1 SendQueue).
 */
public class PacketWriter {

    private final DataOutputStream out;

    public PacketWriter(OutputStream out) {
        this.out = new DataOutputStream(new BufferedOutputStream(out));
    }

    /** 일반 패킷을 통째로 쓴다. 크기 상한을 넘으면 보내기 전에 예외. */
    public void write(Packet packet) throws IOException {
        LinkedHashMap<String, String> all = new LinkedHashMap<>();
        all.put(Packet.KEY_TYPE, packet.getTypeName()); // 첫 줄은 항상 type
        all.putAll(packet.getHeader());
        byte[] header = HeaderFormat.format(all).getBytes(StandardCharsets.UTF_8);
        byte[] data = packet.getData();

        if (header.length > ProtocolInfo.MAX_HEADER_BYTES) {
            throw new IllegalArgumentException("헤더가 너무 큽니다: " + header.length + "B");
        }
        if (data.length > ProtocolInfo.MAX_DATA_BYTES) {
            throw new IllegalArgumentException("데이터가 너무 큽니다: " + data.length + "B");
        }

        out.writeInt(header.length);
        out.write(header);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    /**
     * 파일을 64KB씩 읽어 보낸다 (설계서 2.12).
     * 첨부파일은 제출 범위에서 뼈대만 있으므로 아직 구현하지 않는다 (설계서 1.3).
     */
    public void writeFile(Packet header, Path file, ProgressListener listener) throws IOException {
        throw new UnsupportedOperationException("첨부파일 전송은 제출 후 확장 기능입니다 (설계서 1.3).");
    }
}
