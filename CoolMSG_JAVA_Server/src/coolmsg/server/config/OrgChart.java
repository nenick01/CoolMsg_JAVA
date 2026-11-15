package coolmsg.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import coolmsg.common.file.AtomicFile;
import coolmsg.common.model.OrgTree;

/**
 * 서버 메모리의 조직도 원본 + config/org.txt 저장 (설계서 2.10, 3.2, 3.5, 5장 U54·U55).
 *
 * - 서버를 켤 때 한 번 읽는다. 없거나 읽지 못해도 서버는 동작한다(ORG_NONE).
 * - 관리 화면에서 저장하면 파일과 메모리가 즉시 바뀌고, 그다음 ORG 요청부터 새 조직도를 보낸다.
 * - 저장할 때 맨 위 주석 블록은 보존한다(users.txt와 같은 방식). 저장에 실패하면 메모리는 그대로 둔다.
 * 관리 화면(Swing 스레드)과 접속 처리 스레드가 함께 쓰므로 모든 메서드를 synchronized로 한다.
 */
public class OrgChart {

    public static final String NONE_REASON = "조직도 설정이 없습니다.";

    private final Path file;
    private OrgTree tree = new OrgTree(); // 맨 위 부서가 없으면 "조직도 없음"
    private final List<String> headerComments = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public OrgChart(Path file) {
        this.file = file;
    }

    /**
     * 파일을 읽는다. activeUser를 만족하지 않는 아이디(없는·삭제된 사용자)는 건너뛰고 경고.
     * 읽지 못해도 예외 대신 경고만 남기고 "조직도 없음"으로 둔다(서버는 계속 동작).
     */
    public synchronized void load(Predicate<String> activeUser) {
        tree = new OrgTree();
        headerComments.clear();
        warnings.clear();
        if (!Files.exists(file)) {
            warnings.add("조직도 파일이 없어 조직도 없이 시작합니다 (클라이언트는 등록 순서로 표시): " + file);
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warnings.add("조직도 파일을 읽지 못해 조직도 없이 시작합니다: " + e.getMessage());
            return;
        }
        int bodyStart = 0;
        while (bodyStart < lines.size()) {
            String line = lines.get(bodyStart).strip();
            if (!line.isEmpty() && !line.startsWith("#")) {
                break;
            }
            headerComments.add(line);
            bodyStart++;
        }
        while (!headerComments.isEmpty() && headerComments.get(headerComments.size() - 1).isEmpty()) {
            headerComments.remove(headerComments.size() - 1);
        }
        OrgTree parsed = OrgTree.parse(lines.subList(bodyStart, lines.size()), warnings);
        for (String id : parsed.retainUsers(activeUser)) {
            warnings.add("조직도에 있지만 사용자 목록에 없는(또는 삭제된) 아이디를 건너뜁니다: " + id);
        }
        tree = parsed;
    }

    /** 편집용 복사본 (맨 위 부서가 없을 수 있음). */
    public synchronized OrgTree snapshot() {
        return tree.copy();
    }

    /** 조직도가 있는가 (맨 위 부서가 있음). */
    public synchronized boolean exists() {
        return tree.getRoot() != null;
    }

    /** ORG_OK로 보낼 데이터: 주석 없이, 지금 활성인 사용자만 (U55). 조직도가 없으면 null(→ ORG_NONE). */
    public synchronized String packetText(Predicate<String> activeUser) {
        if (tree.getRoot() == null) {
            return null;
        }
        OrgTree copy = tree.copy();
        copy.retainUsers(activeUser);
        return copy.toText();
    }

    /** 관리 화면 [저장]: 파일을 다시 쓰고, 성공하면 메모리도 바꾼다. */
    public synchronized void save(OrgTree newTree) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String c : headerComments) {
            sb.append(c).append('\n');
        }
        sb.append(newTree.toText());
        Files.createDirectories(file.toAbsolutePath().getParent());
        AtomicFile.writeText(file, sb.toString());
        tree = newTree.copy();
    }

    /**
     * 사용자 삭제 때 그 사람을 조직도에서 빼고 바로 저장한다 (U55). 조직도에 없었으면 아무것도 하지 않는다.
     *
     * @throws IOException 저장 실패 (메모리는 그대로). 부른 쪽은 삭제를 계속하고 경고만 남긴다
     */
    public synchronized boolean removeUser(String userId) throws IOException {
        OrgTree copy = tree.copy();
        if (copy.retainUsers(id -> !id.equals(userId)).isEmpty()) {
            return false;
        }
        save(copy);
        return true;
    }

    /** 사용자 관리 표의 소속 부서 열. 미배치면 null. */
    public synchronized String deptNameOf(String userId) {
        return tree.deptNameOf(userId);
    }

    public synchronized List<String> getWarnings() {
        return List.copyOf(warnings);
    }
}
