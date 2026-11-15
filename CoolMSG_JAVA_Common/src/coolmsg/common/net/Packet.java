package coolmsg.common.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import coolmsg.common.FormatException;
import coolmsg.common.file.HeaderFormat;

/**
 * 패킷 하나 = 종류(type) + 헤더(키=값, 순서 유지) + 데이터(바이트).  설계서 2.1
 *
 * 헤더의 키와 값은 넣을 때 바로 검사한다(설계서 5장 U1). 그래서 규칙에 어긋난 패킷은 만들어지지 않는다.
 * 읽기·쓰기는 PacketReader / PacketWriter가 한다.
 */
public class Packet {

    public static final String KEY_TYPE = HeaderKeys.TYPE;
    public static final String KEY_REQ_ID = HeaderKeys.REQ_ID;
    public static final String KEY_REASON = HeaderKeys.REASON;

    /** 로그에 찍을 때 값을 가리는 키. */
    private static final String KEY_PASSWORD = HeaderKeys.PASSWORD;

    private final String typeName;
    private final LinkedHashMap<String, String> header = new LinkedHashMap<>();
    private byte[] data = new byte[0];

    public Packet(PacketType type) {
        this(Objects.requireNonNull(type, "type").name());
    }

    /**
     * PacketReader가 모르는 종류의 패킷도 읽을 수 있게 하는 생성자.
     * (서버는 모르는 종류를 받으면 연결을 끊지 않고 ERROR로 응답한다. 설계서 3.7)
     */
    Packet(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            throw new IllegalArgumentException("패킷 종류가 비어 있습니다.");
        }
        HeaderFormat.checkValue(KEY_TYPE, typeName);
        this.typeName = typeName;
    }

    /** 요청에 대한 응답을 만든다. 요청의 reqId를 그대로 붙인다. */
    public static Packet replyTo(Packet request, PacketType type) {
        Packet reply = new Packet(type);
        String reqId = request.get(KEY_REQ_ID);
        if (reqId != null) {
            reply.put(KEY_REQ_ID, reqId);
        }
        return reply;
    }

    // ---------- 종류 ----------

    /** 패킷 종류. 모르는 종류면 null. */
    public PacketType getType() {
        return PacketType.fromName(typeName);
    }

    public String getTypeName() {
        return typeName;
    }

    // ---------- 헤더 쓰기 ----------

    public Packet put(String key, String value) {
        if (KEY_TYPE.equals(key)) {
            throw new IllegalArgumentException("type은 생성자에서 정합니다.");
        }
        HeaderFormat.checkKey(key);
        HeaderFormat.checkValue(key, value);
        header.put(key, value);
        return this;
    }

    public Packet put(String key, long value) {
        return put(key, Long.toString(value));
    }

    public Packet putAll(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            put(e.getKey(), e.getValue());
        }
        return this;
    }

    /** 목록을 쉼표로 이어 넣는다 (예: receivers=lee,park). 항목에 쉼표가 있으면 예외. */
    public Packet putList(String key, Collection<String> values) {
        for (String v : values) {
            if (v.isEmpty() || v.indexOf(',') >= 0) {
                throw new IllegalArgumentException("목록 항목이 비었거나 쉼표가 있습니다: " + key);
            }
        }
        return put(key, String.join(",", values));
    }

    public Packet setReqId(int reqId) {
        return put(KEY_REQ_ID, reqId);
    }

    // ---------- 헤더 읽기 ----------

    /** 값. 없으면 null. */
    public String get(String key) {
        return header.get(key);
    }

    public boolean has(String key) {
        return header.containsKey(key);
    }

    /** 반드시 있어야 하는 값. 없으면 FormatException. */
    public String require(String key) throws FormatException {
        String value = header.get(key);
        if (value == null || value.isEmpty()) {
            throw new FormatException(typeName + " 패킷에 " + key + "가 없습니다.");
        }
        return value;
    }

    /** 반드시 있어야 하는 정수 값. */
    public int requireInt(String key) throws FormatException {
        String value = require(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new FormatException(key + "가 정수가 아닙니다: " + value, e);
        }
    }

    /** reqId. 없거나 정수가 아니면 -1. */
    public int getReqId() {
        String value = header.get(KEY_REQ_ID);
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 쉼표로 이어진 목록. 키가 없으면 빈 목록. */
    public List<String> getList(String key) {
        List<String> list = new ArrayList<>();
        String value = header.get(key);
        if (value != null && !value.isEmpty()) {
            for (String item : value.split(",")) {
                if (!item.isEmpty()) {
                    list.add(item);
                }
            }
        }
        return list;
    }

    /** 헤더 전체 (읽기 전용, type 제외, 순서 유지). */
    public Map<String, String> getHeader() {
        return Collections.unmodifiableMap(header);
    }

    // ---------- 데이터 ----------

    /** 데이터 복사본. (밖에서 배열을 고쳐도 패킷은 바뀌지 않게) */
    public byte[] getData() {
        return data.clone();
    }

    public int getDataLength() {
        return data.length;
    }

    public Packet setData(byte[] data) {
        this.data = (data == null) ? new byte[0] : data.clone();
        return this;
    }

    /** 데이터를 UTF-8 문자열로 넣는다 (본문, 사용자 목록 등). */
    public Packet setText(String text) {
        return setData(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 데이터를 UTF-8 문자열로 읽는다. */
    public String getText() {
        return new String(data, StandardCharsets.UTF_8);
    }

    /** 로그용 문자열. 비밀번호 값은 가린다. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(typeName).append(" {");
        boolean first = true;
        for (Map.Entry<String, String> e : header.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            String value = KEY_PASSWORD.equals(e.getKey()) ? "****" : e.getValue();
            sb.append(e.getKey()).append('=').append(value);
        }
        return sb.append("} data=").append(data.length).append("B").toString();
    }
}
