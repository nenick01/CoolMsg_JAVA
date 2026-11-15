package coolmsg.common.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import coolmsg.common.FormatException;

/**
 * "키=값 줄들 + 빈 줄 + 본문" 형식의 파일 (설계서 3.2 메시지 파일, 4.2 로컬 보관함).
 *
 * <pre>
 * messageId=3f2a...
 * sender=kim
 * delivered.lee=true      ← 서버 전용 키
 *                         ← 빈 줄 = 머리글 끝
 * 본문 (줄바꿈, '=' 모두 자유)
 * </pre>
 *
 * 이 클래스는 자기가 모르는 키도 지우지 않고 순서 그대로 보존한다.
 * 그래서 서버(delivered.*, access)와 클라이언트(read, state.*)가 같은 형식에 각자 필요한 키를 덧붙여 쓸 수 있다.
 */
public class HeaderBodyFile {

    private final LinkedHashMap<String, String> header = new LinkedHashMap<>();
    private String body;

    public HeaderBodyFile() {
        this.body = "";
    }

    public HeaderBodyFile(Map<String, String> header, String body) {
        putAll(header);
        setBody(body);
    }

    // ---------- 머리글 ----------

    public String get(String key) {
        return header.get(key);
    }

    public boolean has(String key) {
        return header.containsKey(key);
    }

    /** 키를 추가하거나 바꾼다. 이미 있는 키는 자리를 유지한 채 값만 바뀐다. */
    public void put(String key, String value) {
        HeaderFormat.checkKey(key);
        HeaderFormat.checkValue(key, value);
        header.put(key, value);
    }

    public void putAll(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    public void remove(String key) {
        header.remove(key);
    }

    /** 머리글 전체 (읽기 전용, 순서 유지). */
    public Map<String, String> getHeader() {
        return Collections.unmodifiableMap(header);
    }

    // ---------- 본문 ----------

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        if (body == null) {
            throw new IllegalArgumentException("본문이 null입니다.");
        }
        this.body = body;
    }

    // ---------- 텍스트 변환 ----------

    /** 파일에 쓸 전체 텍스트: 머리글 + 빈 줄 + 본문. */
    public String toText() {
        return HeaderFormat.format(header) + "\n" + body;
    }

    /** 전체 텍스트를 읽는다. 머리글 끝을 알리는 빈 줄이 없으면 형식 오류. */
    public static HeaderBodyFile parse(String text) throws FormatException {
        String headerText;
        String bodyText;
        if (text.startsWith("\n")) {
            // 머리글이 하나도 없는 경우: 첫 줄이 바로 빈 줄
            headerText = "";
            bodyText = text.substring(1);
        } else {
            int end = text.indexOf("\n\n");
            if (end < 0) {
                throw new FormatException("머리글 끝을 알리는 빈 줄이 없습니다.");
            }
            headerText = text.substring(0, end + 1); // 마지막 머리글 줄의 \n까지
            bodyText = text.substring(end + 2);
        }
        return new HeaderBodyFile(HeaderFormat.parse(headerText), bodyText);
    }

    // ---------- 파일 ----------

    /** 파일을 읽는다. UTF-8이 아니거나 형식이 틀리면 FormatException. */
    public static HeaderBodyFile read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return parse(HeaderFormat.decodeUtf8(bytes));
        } catch (FormatException e) {
            throw new FormatException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** 파일에 저장한다. 반쯤 쓴 파일이 남지 않게 AtomicFile을 쓴다. */
    public void write(Path file) throws IOException {
        AtomicFile.write(file, toText().getBytes(StandardCharsets.UTF_8));
    }
}
