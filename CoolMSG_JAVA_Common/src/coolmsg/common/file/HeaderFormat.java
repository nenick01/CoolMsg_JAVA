package coolmsg.common.file;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import coolmsg.common.FormatException;

/**
 * "키=값" 줄들의 읽기·쓰기 규칙. 패킷 헤더와 메시지 파일 머리글이 똑같이 쓴다.
 *
 * 규칙 (설계서 2.1, 5장 U1 — 엄격하게):
 * - 줄바꿈은 \n만. \r이 섞여 있으면 형식 오류.
 * - 키와 값은 처음 나오는 '='에서만 나눈다. 값 안의 '='는 그대로 값이다.
 * - 같은 키가 두 번 나오면 형식 오류.
 * - 값에 줄바꿈이 있으면 쓰기 전에 예외 (잘못된 데이터를 아예 만들지 않는다).
 *
 * 사람이 메모장으로 고치는 설정 파일(server.txt 등)에는 쓰지 않는다. (5장 U11)
 */
public final class HeaderFormat {

    private HeaderFormat() {
    }

    /** 키가 규칙에 맞는지 확인한다. 비어 있거나 '=', 줄바꿈이 있으면 예외. */
    public static void checkKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("키가 비어 있습니다.");
        }
        if (key.indexOf('=') >= 0 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("키에 '='나 줄바꿈을 쓸 수 없습니다: " + key);
        }
    }

    /** 값이 규칙에 맞는지 확인한다. null이거나 줄바꿈이 있으면 예외. */
    public static void checkValue(String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("값이 null입니다: " + key);
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("값에 줄바꿈을 쓸 수 없습니다: " + key);
        }
    }

    /** 맵을 "키=값\n" 줄들로 만든다. 순서는 맵의 순서를 따른다. */
    public static String format(Map<String, String> header) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : header.entrySet()) {
            checkKey(e.getKey());
            checkValue(e.getKey(), e.getValue());
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /**
     * "키=값\n" 줄들을 읽어 순서가 유지되는 맵으로 만든다.
     * 빈 문자열은 빈 맵이다. 그 밖에는 마지막 줄도 \n으로 끝나야 한다.
     */
    public static LinkedHashMap<String, String> parse(String text) throws FormatException {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (text.isEmpty()) {
            return map;
        }
        if (text.indexOf('\r') >= 0) {
            throw new FormatException("머리글에 \\r이 있습니다 (줄바꿈은 \\n만 허용).");
        }
        if (!text.endsWith("\n")) {
            throw new FormatException("머리글의 마지막 줄이 줄바꿈으로 끝나지 않습니다.");
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNo = i + 1;
            if (line.isEmpty()) {
                throw new FormatException(lineNo + "번째 줄이 비어 있습니다.");
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new FormatException(lineNo + "번째 줄에 '='가 없습니다.");
            }
            if (eq == 0) {
                throw new FormatException(lineNo + "번째 줄의 키가 비어 있습니다.");
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (map.containsKey(key)) {
                throw new FormatException("같은 키가 두 번 나왔습니다: " + key);
            }
            map.put(key, value);
        }
        return map;
    }

    /**
     * UTF-8 바이트를 문자열로 바꾼다. 잘못된 UTF-8이면 '?'로 바꾸지 않고 형식 오류로 본다.
     * (new String(bytes, UTF_8)은 깨진 글자를 조용히 바꿔 버려서 오류를 숨긴다.)
     */
    public static String decodeUtf8(byte[] bytes) throws FormatException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new FormatException("UTF-8 형식이 아닙니다.", e);
        }
    }
}
