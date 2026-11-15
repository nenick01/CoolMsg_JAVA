package coolmsg.client.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import coolmsg.client.model.MailItem;
import coolmsg.common.FormatException;
import coolmsg.common.file.HeaderBodyFile;
import coolmsg.common.model.MessageData;

/**
 * 내 PC의 보관함 ~/.coolmsg/{아이디}/ (설계서 4.2, 5장 U38).
 *
 *   inbox/{messageId}.txt   받은 메시지 (+ read=true/false)
 *   sent/{messageId}.txt    보낸 메시지
 *   deleted.txt             지운 받은 메시지의 ID (한 줄에 하나)
 *
 * 받기 스레드(새 메시지 저장)와 Swing 스레드(목록, 읽음, 삭제)가 함께 쓰므로 모든 메서드를 synchronized로 한다.
 * 파일 쓰기는 HeaderBodyFile.write(AtomicFile)라 반쯤 쓴 파일이 남지 않는다.
 */
public class Mailbox {

    public static final String KEY_READ = "read";

    private final Path inboxDir;
    private final Path sentDir;
    private final Path deletedFile;
    /** deleted.txt 내용. 처음 필요할 때 읽는다. */
    private Set<String> deletedIds;

    public Mailbox(Path dir) {
        this.inboxDir = dir.resolve("inbox");
        this.sentDir = dir.resolve("sent");
        this.deletedFile = dir.resolve("deleted.txt");
    }

    /** saveReceived의 결과. 어느 경우든 저장에 실패하지 않았으면 ACK를 보낸다. */
    public enum ReceiveResult {
        /** 새로 저장함 → 화면 알림 */
        SAVED,
        /** 이미 받은 메시지 (ACK 유실 뒤 재전달) → 저장하지 않음 */
        DUPLICATE,
        /** 내가 지운 메시지 (U38) → 되살리지 않음 */
        DELETED_BEFORE
    }

    /**
     * 받은 메시지를 저장한다 (읽지 않음 상태).
     *
     * @throws IOException 저장 실패. 이때는 ACK를 보내지 않아야 다음 로그인 때 다시 받는다
     */
    public synchronized ReceiveResult saveReceived(MessageData data) throws IOException {
        String id = requireValidId(data.getMessageId());
        if (deleted().contains(id)) {
            return ReceiveResult.DELETED_BEFORE;
        }
        Path file = inboxDir.resolve(id + ".txt");
        if (Files.exists(file)) {
            return ReceiveResult.DUPLICATE;
        }
        HeaderBodyFile f = new HeaderBodyFile(data.toHeader(), data.getBody());
        f.put(KEY_READ, "false");
        Files.createDirectories(inboxDir);
        f.write(file);
        return ReceiveResult.SAVED;
    }

    /** 보낸 메시지를 저장한다 (SEND_OK를 받은 뒤, 보낸 사람·보낸 시각이 채워진 것). 같은 ID면 덮어쓴다. */
    public synchronized void saveSent(MessageData data) throws IOException {
        String id = requireValidId(data.getMessageId());
        Files.createDirectories(sentDir);
        new HeaderBodyFile(data.toHeader(), data.getBody()).write(sentDir.resolve(id + ".txt"));
    }

    /** 받은 메시지 목록, 최신이 위 (U42). 읽지 못하는 파일은 건너뛴다. */
    public synchronized List<MailItem> inbox() {
        return list(inboxDir, true);
    }

    /** 보낸 메시지 목록, 최신이 위. */
    public synchronized List<MailItem> sent() {
        return list(sentDir, false);
    }

    /** 안 읽은 받은 메시지 수 (메인 창 [메시지 관리함 (n)]). */
    public synchronized int unreadCount() {
        int n = 0;
        for (MailItem m : list(inboxDir, true)) {
            if (!m.isRead()) {
                n++;
            }
        }
        return n;
    }

    /** 읽음으로 표시한다. 이번에 바뀌었으면 true (5단계에서 이때 READ를 한 번만 보낸다). */
    public synchronized boolean markRead(String messageId) throws IOException {
        if (!MessageData.isValidMessageId(messageId)) {
            return false;
        }
        Path file = inboxDir.resolve(messageId + ".txt");
        if (!Files.exists(file)) {
            return false;
        }
        HeaderBodyFile f = HeaderBodyFile.read(file);
        if ("true".equals(f.get(KEY_READ))) {
            return false;
        }
        f.put(KEY_READ, "true");
        f.write(file);
        return true;
    }

    /** 받은 메시지 삭제: 파일은 지우고 ID만 deleted.txt에 남긴다 (U38). 내 PC에서만 지운다. */
    public synchronized void deleteReceived(String messageId) throws IOException {
        if (!MessageData.isValidMessageId(messageId)) {
            return;
        }
        if (deleted().add(messageId)) {
            Files.createDirectories(deletedFile.getParent());
            Files.write(deletedFile, (messageId + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Files.deleteIfExists(inboxDir.resolve(messageId + ".txt"));
    }

    /** 보낸 메시지 삭제: 내 보낸 내역에서만 지운다 (REQ-F-106). */
    public synchronized void deleteSent(String messageId) throws IOException {
        if (MessageData.isValidMessageId(messageId)) {
            Files.deleteIfExists(sentDir.resolve(messageId + ".txt"));
        }
    }

    // =====================================================================

    private List<MailItem> list(Path dir, boolean inbox) {
        List<MailItem> items = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return items;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path p : stream) {
                try {
                    HeaderBodyFile f = HeaderBodyFile.read(p);
                    MessageData data = MessageData.fromHeader(f.getHeader(), f.getBody());
                    items.add(new MailItem(data, !inbox || "true".equals(f.get(KEY_READ))));
                } catch (IOException e) {
                    System.err.println("보관함 파일을 읽지 못해 건너뜁니다: " + p + " (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            System.err.println("보관함 폴더를 읽지 못했습니다: " + dir + " (" + e.getMessage() + ")");
        }
        // 최신이 위 (시각이 없는 이상한 파일은 맨 아래: reversed 전에 nullsFirst)
        items.sort(Comparator.comparing(MailItem::getSentAt,
                Comparator.nullsFirst(Comparator.<java.time.LocalDateTime>naturalOrder())).reversed());
        return items;
    }

    private Set<String> deleted() throws IOException {
        if (deletedIds == null) {
            Set<String> ids = new HashSet<>();
            if (Files.exists(deletedFile)) {
                for (String line : Files.readAllLines(deletedFile, StandardCharsets.UTF_8)) {
                    String id = line.strip();
                    if (MessageData.isValidMessageId(id)) {
                        ids.add(id);
                    }
                }
            }
            deletedIds = ids;
        }
        return deletedIds;
    }

    private static String requireValidId(String id) throws FormatException {
        if (!MessageData.isValidMessageId(id)) {
            throw new FormatException("messageId가 UUID 형식이 아닙니다: " + id);
        }
        return id;
    }
}
