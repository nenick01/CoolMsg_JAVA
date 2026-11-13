package coolmsg.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자 목록 파일(config/users.txt) 읽기.
 *
 * 형식: 아이디|이름|솔트|비밀번호해시 (한 줄에 한 명)
 * - 적힌 순서 = 등록 순서. 순서를 지키기 위해 ArrayList에 담는다.
 *
 * 지금은 읽기만 한다. 추가·수정·삭제와 저장은 다음 단계에서 구현한다.
 */
public class UserStore {

    private final Path file;
    private final List<UserAccount> users = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public UserStore(Path file) {
        this.file = file;
    }

    /** 파일을 다시 읽는다. 형식이 잘못된 줄은 건너뛰고 경고 목록에 남긴다. */
    public void load() throws IOException {
        users.clear();
        warnings.clear();
        if (!Files.exists(file)) {
            warnings.add("사용자 목록 파일이 없습니다: " + file.toAbsolutePath());
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 4) {
                warnings.add((i + 1) + "번째 줄 형식 오류 (칸 수 " + parts.length + "개, 4개여야 함)");
                continue;
            }
            users.add(new UserAccount(parts[0], parts[1], parts[2], parts[3]));
        }
    }

    public List<UserAccount> getUsers() {
        return users;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
