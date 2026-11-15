package coolmsg.server.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import coolmsg.common.file.AtomicFile;

/**
 * 사용자별 미전달 색인 data/pending/{아이디}.txt (설계서 3.8, 5장 U14·U15).
 *
 * - 한 줄에 messageId 하나, 저장된 순서대로. 메시지를 저장할 때 받는 사람마다 추가하고, ACK가 올 때만 지운다.
 * - 메시지 파일이 "사실"이고 색인은 "찾기 위한 목록"이다. 어긋나면 메시지 파일 기준으로 다시 만든다(rebuild).
 * - 같은 아이디의 추가·삭제·읽기는 아이디별 잠금 안에서만 한다(C10).
 * - 정상 종료 표시(.clean): [서버 시작] 때 있으면 지우고 색인을 믿고, 없으면 다시 만든다. [서버 중지] 때 만든다(3.8.6).
 */
public class PendingIndex {

    public static final String CLEAN_MARKER = ".clean";

    private final Path dir;
    private final LockStripes locks = new LockStripes();

    public PendingIndex(Path dir) {
        this.dir = dir;
    }

    /** 이 사람이 아직 받지 않은 메시지 ID 하나를 끝에 덧붙인다. */
    public void add(String userId, String messageId) throws IOException {
        synchronized (locks.lockFor(userId)) {
            Files.createDirectories(dir);
            Files.write(fileOf(userId), (messageId + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /** 이 사람의 색인에서 그 ID를 지운다 (ACK가 왔을 때). 파일 전체를 원자적으로 다시 쓴다. */
    public void remove(String userId, String messageId) throws IOException {
        synchronized (locks.lockFor(userId)) {
            Path file = fileOf(userId);
            if (!Files.exists(file)) {
                return;
            }
            Set<String> ids = readIds(file);
            if (ids.remove(messageId)) {
                writeIds(file, ids);
            }
        }
    }

    /** 이 사람이 아직 받지 않은 메시지 ID 목록 (저장 순서, 중복 제거). */
    public List<String> list(String userId) throws IOException {
        synchronized (locks.lockFor(userId)) {
            Path file = fileOf(userId);
            return Files.exists(file) ? new ArrayList<>(readIds(file)) : new ArrayList<>();
        }
    }

    // =====================================================================
    // 정상 종료 표시와 다시 만들기 (3.8.6)
    // =====================================================================

    /** [서버 시작] 때: 정상 종료 표시가 있었으면 지우고 true. 없었으면 false(→ 다시 만들어야 함). */
    public boolean consumeCleanMarker() throws IOException {
        Path marker = dir.resolve(CLEAN_MARKER);
        if (Files.exists(marker)) {
            Files.delete(marker);
            return true;
        }
        return false;
    }

    /** [서버 중지] 때: 정상 종료 표시를 만든다. */
    public void writeCleanMarker() throws IOException {
        Files.createDirectories(dir);
        AtomicFile.writeText(dir.resolve(CLEAN_MARKER), "정상 종료 " + LocalDateTime.now() + "\n");
    }

    /**
     * 메시지 파일 기준으로 색인을 모두 다시 만든다.
     * delivered.{아이디}=false 인 사람의 색인에 넣는다. 삭제된 사용자도 포함한다(U19).
     * 메시지는 보낸 시각 순서로 넣는다. 손상된 파일은 건너뛰고 problems에 적는다.
     *
     * 서버가 접속을 받기 전에(아무도 색인을 쓰지 않을 때) 부른다.
     */
    public int rebuild(MessageStore store, List<String> problems) throws IOException {
        List<StoredMessage> messages = new ArrayList<>();
        for (String id : store.listIds()) {
            try {
                StoredMessage m = store.load(id);
                if (m != null) {
                    messages.add(m);
                }
            } catch (IOException e) {
                problems.add("메시지 파일을 읽지 못해 건너뜁니다: " + id + " (" + e.getMessage() + ")");
            }
        }
        messages.sort(Comparator.comparing(m -> m.getData().getSentAt(),
                Comparator.nullsFirst(Comparator.naturalOrder())));

        Map<String, Set<String>> byUser = new HashMap<>();
        for (StoredMessage m : messages) {
            for (String userId : m.recipients()) {
                if (!m.isDelivered(userId)) {
                    byUser.computeIfAbsent(userId, k -> new LinkedHashSet<>()).add(m.getData().getMessageId());
                }
            }
        }
        // 기존 색인 파일을 모두 지우고 새로 쓴다
        Files.createDirectories(dir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path p : stream) {
                Files.delete(p);
            }
        }
        int count = 0;
        for (Map.Entry<String, Set<String>> e : byUser.entrySet()) {
            writeIds(fileOf(e.getKey()), e.getValue());
            count += e.getValue().size();
        }
        return count;
    }

    // =====================================================================

    private Path fileOf(String userId) {
        return dir.resolve(userId + ".txt");
    }

    private static Set<String> readIds(Path file) throws IOException {
        Set<String> ids = new LinkedHashSet<>(); // 중복 제거, 순서 유지
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String id = line.strip();
            if (MessageStore.isValidMessageId(id)) { // 반쯤 쓴 줄 같은 이상한 줄은 무시
                ids.add(id);
            }
        }
        return ids;
    }

    private static void writeIds(Path file, Set<String> ids) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            sb.append(id).append('\n');
        }
        AtomicFile.writeText(file, sb.toString());
    }
}
