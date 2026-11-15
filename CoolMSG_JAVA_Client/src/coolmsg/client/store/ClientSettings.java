package coolmsg.client.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import coolmsg.common.file.AtomicFile;

/**
 * 마지막 서버 주소·아이디 (설계서 4.3, 5장 U34). 비밀번호는 저장하지 않는다.
 *
 * 파일 형식 (키=값):
 *   server=192.168.0.10:9100
 *   userId=kim
 *
 * 로그인에 성공했을 때만 저장한다. 사람이 열어 볼 수 있는 파일이라 관대하게 읽고(U11),
 * 읽지 못하면 빈 값으로 시작한다(다음 로그인 때 다시 저장되므로 잃을 것이 없다).
 */
public class ClientSettings {

    private static final String KEY_SERVER = "server";
    private static final String KEY_USER_ID = "userId";
    public static final String DEFAULT_SERVER = "localhost";

    private String server = DEFAULT_SERVER;
    private String userId = "";

    /** 파일을 읽는다. 없거나 읽지 못하면 기본값(localhost, 빈 아이디). */
    public static ClientSettings load(Path file) {
        ClientSettings s = new ClientSettings();
        if (!Files.exists(file)) {
            return s;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.strip();
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (KEY_SERVER.equals(key) && !value.isEmpty()) {
                    s.server = value;
                } else if (KEY_USER_ID.equals(key)) {
                    s.userId = value;
                }
            }
        } catch (IOException e) {
            // 읽지 못하면 기본값으로 시작한다
        }
        return s;
    }

    /** 파일에 저장한다. 폴더가 없으면 만든다(AtomicFile). */
    public void save(Path file) throws IOException {
        AtomicFile.writeText(file, KEY_SERVER + "=" + server + "\n" + KEY_USER_ID + "=" + userId + "\n");
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server.replace("\n", "").replace("\r", "");
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId.replace("\n", "").replace("\r", "");
    }
}
