package coolmsg.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import coolmsg.server.security.PasswordHasher;

/**
 * 관리자 비밀번호 설정 파일(config/admin.txt).
 *
 * 형식 (키=값):
 *   salt=16진수
 *   hash=16진수
 *
 * 이 파일은 Git에 올리지 않는다. 비밀번호를 잊으면 파일을 지우고 서버를 다시 실행해 새로 정한다.
 */
public class AdminConfig {

    private final Path file;
    private String salt;
    private String hash;

    public AdminConfig(Path file) {
        this.file = file;
    }

    /** 관리자 비밀번호가 이미 정해져 있는지 (파일이 있고 읽을 수 있는지). */
    public boolean isPasswordSet() {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            load();
            return salt != null && hash != null;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean checkPassword(char[] password) {
        if (salt == null || hash == null) {
            return false;
        }
        return PasswordHasher.matches(salt, hash, password);
    }

    /** 새 관리자 비밀번호를 저장한다. 폴더가 없으면 만든다. */
    public void savePassword(char[] password) throws IOException {
        String newSalt = PasswordHasher.newSalt();
        String newHash = PasswordHasher.hash(newSalt, password);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        List<String> lines = List.of(
                "# CoolMsg 관리자 비밀번호 (해시). 비밀번호를 잊으면 이 파일을 지우고 서버를 다시 실행하세요.",
                "salt=" + newSalt,
                "hash=" + newHash);
        Files.write(file, lines, StandardCharsets.UTF_8);
        this.salt = newSalt;
        this.hash = newHash;
    }

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (key.equals("salt")) {
                salt = value;
            } else if (key.equals("hash")) {
                hash = value;
            }
        }
    }
}
