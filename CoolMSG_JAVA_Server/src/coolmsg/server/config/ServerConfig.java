package coolmsg.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import coolmsg.common.ProtocolInfo;

/**
 * 서버 설정 (config/server.txt).  설계서 3.2, 5장 U21
 *
 * - 사람이 메모장으로 고치는 파일이라 관대하게 읽는다(U11): 줄 끝 공백·\r 허용, '#' 주석과 빈 줄 무시.
 * - 범위를 벗어나거나 숫자가 아니면 그 항목만 기본값을 쓰고 경고를 남긴다.
 * - 파일이 없거나 읽지 못하면 모두 기본값을 쓰고 경고를 남긴다.
 * - 서버를 켤 때 한 번만 읽는다. 바꾸려면 파일을 고친 뒤 서버를 다시 켠다.
 */
public class ServerConfig {

    public static final String KEY_PORT = "port";
    public static final String KEY_SERVER_NAME = "serverName";
    public static final String KEY_BODY_RETENTION_DAYS = "bodyRetentionDays";
    public static final String KEY_ATTACHMENT_RETENTION_DAYS = "attachmentRetentionDays";
    public static final String KEY_MAX_ATTACHMENT_MB = "maxAttachmentMB";

    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;
    public static final int MAX_SERVER_NAME_LENGTH = 30;
    /** 첨부 최대 크기의 상한. 패킷 데이터 상한(11MB)을 넘을 수 없다. */
    public static final int MAX_ATTACHMENT_MB_LIMIT = (int) (ProtocolInfo.MAX_ATTACHMENT_BYTES / (1024 * 1024));

    public static final String DEFAULT_SERVER_NAME = "CoolMsg 서버";
    public static final int DEFAULT_BODY_RETENTION_DAYS = 90;
    public static final int DEFAULT_ATTACHMENT_RETENTION_DAYS = 30;

    private int port = ProtocolInfo.DEFAULT_PORT;
    private String serverName = DEFAULT_SERVER_NAME;
    private int bodyRetentionDays = DEFAULT_BODY_RETENTION_DAYS;
    private int attachmentRetentionDays = DEFAULT_ATTACHMENT_RETENTION_DAYS;
    private int maxAttachmentMB = MAX_ATTACHMENT_MB_LIMIT;

    private final List<String> warnings = new ArrayList<>();

    private ServerConfig() {
    }

    /** 모든 값이 기본값인 설정. */
    public static ServerConfig defaults() {
        return new ServerConfig();
    }

    /** 설정 파일을 읽는다. 어떤 경우에도 예외 없이 사용할 수 있는 설정을 돌려준다(문제는 경고로 남김). */
    public static ServerConfig load(Path file) {
        ServerConfig config = new ServerConfig();
        if (!Files.exists(file)) {
            config.warn("설정 파일이 없어 기본값을 사용합니다: " + file);
            return config;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            config.warn("설정 파일을 읽지 못해 기본값을 사용합니다: " + file + " (" + e.getMessage() + ")");
            return config;
        }
        config.apply(parse(lines, config.warnings));
        return config;
    }

    /** "키=값" 줄들을 관대하게 읽는다. 문제가 있으면 warnings에 적는다. */
    private static Map<String, String> parse(List<String> lines, List<String> warnings) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip(); // 줄 끝 \r, 공백 제거 (U11)
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                warnings.add((i + 1) + "번째 줄을 이해할 수 없어 건너뜁니다: " + line);
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (values.containsKey(key)) {
                warnings.add(key + "가 두 번 있어 나중 값(" + value + ")을 사용합니다.");
            }
            values.put(key, value);
        }
        return values;
    }

    private void apply(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            switch (key) {
                case KEY_PORT -> port = intInRange(key, value, MIN_PORT, MAX_PORT, port);
                case KEY_SERVER_NAME -> serverName = nameInRange(value);
                case KEY_BODY_RETENTION_DAYS ->
                        bodyRetentionDays = intInRange(key, value, 1, Integer.MAX_VALUE, bodyRetentionDays);
                case KEY_ATTACHMENT_RETENTION_DAYS ->
                        attachmentRetentionDays = intInRange(key, value, 1, Integer.MAX_VALUE, attachmentRetentionDays);
                case KEY_MAX_ATTACHMENT_MB ->
                        maxAttachmentMB = intInRange(key, value, 1, MAX_ATTACHMENT_MB_LIMIT, maxAttachmentMB);
                default -> warn("모르는 설정 키라 무시합니다: " + key);
            }
        }
    }

    private int intInRange(String key, String value, int min, int max, int defaultValue) {
        try {
            int n = Integer.parseInt(value);
            if (n >= min && n <= max) {
                return n;
            }
            String range = (max == Integer.MAX_VALUE) ? min + " 이상" : min + "~" + max;
            warn(key + "=" + value + "는 허용 범위(" + range + ")를 벗어나 기본값 " + defaultValue + "을(를) 사용합니다.");
        } catch (NumberFormatException e) {
            warn(key + "=" + value + "는 숫자가 아니라 기본값 " + defaultValue + "을(를) 사용합니다.");
        }
        return defaultValue;
    }

    private String nameInRange(String value) {
        if (!value.isEmpty() && value.length() <= MAX_SERVER_NAME_LENGTH) {
            return value;
        }
        warn(KEY_SERVER_NAME + "는 1~" + MAX_SERVER_NAME_LENGTH + "자여야 해서 기본값을 사용합니다.");
        return serverName;
    }

    private void warn(String message) {
        warnings.add(message);
    }

    // ---------- getter ----------

    public int getPort() {
        return port;
    }

    public String getServerName() {
        return serverName;
    }

    public int getBodyRetentionDays() {
        return bodyRetentionDays;
    }

    public int getAttachmentRetentionDays() {
        return attachmentRetentionDays;
    }

    public int getMaxAttachmentMB() {
        return maxAttachmentMB;
    }

    /** 읽으면서 생긴 경고 (읽기 전용 복사본). */
    public List<String> getWarnings() {
        return List.copyOf(warnings);
    }
}
