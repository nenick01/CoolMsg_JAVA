package coolmsg.server.store;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import coolmsg.common.FormatException;
import coolmsg.common.file.HeaderBodyFile;
import coolmsg.common.model.MessageData;

/**
 * 메시지 파일 저장소 data/messages/{messageId}.txt (설계서 3.8).
 *
 * - 메시지 파일이 "사실"이다. 받았는지(delivered.{아이디})의 진짜 기록은 여기에 있다(3.8.2).
 * - 같은 메시지의 "읽기 → 고치기 → 쓰기"는 메시지 ID별 잠금 안에서만 한다(C1, U16).
 * - 파일 이름으로 쓰이므로 messageId는 UUID 형식만 받는다(U35).
 */
public class MessageStore {

    private final Path dir;
    private final LockStripes locks = new LockStripes();

    public MessageStore(Path dir) {
        this.dir = dir;
    }

    /** 형식 규칙은 Common의 MessageData.isValidMessageId 하나 (클라이언트 보관함과 같은 규칙). */
    public static boolean isValidMessageId(String id) {
        return MessageData.isValidMessageId(id);
    }

    /** create의 결과: 새로 저장했는지, 이미 있던 메시지인지. */
    public static final class CreateResult {
        private final boolean created;
        private final StoredMessage message;

        CreateResult(boolean created, StoredMessage message) {
            this.created = created;
            this.message = message;
        }

        /** true면 새로 저장, false면 같은 ID가 이미 있었음(message는 기존 것). */
        public boolean isCreated() {
            return created;
        }

        public StoredMessage getMessage() {
            return message;
        }
    }

    /**
     * 메시지를 저장한다. 같은 ID의 파일이 이미 있으면 저장하지 않고 기존 메시지를 돌려준다(재전송 중복 방지).
     * 받는 사람·참조 대상은 모두 delivered=false로 시작한다.
     */
    public CreateResult create(MessageData data) throws IOException {
        String id = data.getMessageId();
        requireValidId(id);
        synchronized (locks.lockFor(id)) {
            Path file = fileOf(id);
            if (Files.exists(file)) {
                return new CreateResult(false, StoredMessage.from(HeaderBodyFile.read(file)));
            }
            StoredMessage m = StoredMessage.create(data);
            m.getFile().write(file); // AtomicFile: 반쯤 쓴 파일이 남지 않는다
            return new CreateResult(true, m);
        }
    }

    /** 메시지를 읽는다. 없으면 null. 손상되면 FormatException. */
    public StoredMessage load(String id) throws IOException {
        if (!isValidMessageId(id)) {
            return null;
        }
        synchronized (locks.lockFor(id)) {
            Path file = fileOf(id);
            return Files.exists(file) ? StoredMessage.from(HeaderBodyFile.read(file)) : null;
        }
    }

    /** markDelivered의 결과. */
    public enum DeliveryUpdate {
        /** 이번에 false → true로 바뀜 */
        CHANGED,
        /** 이미 true였음 */
        ALREADY,
        /** 이 사람은 받는 사람·참조 대상이 아님, 또는 메시지가 없음 */
        NOT_RECIPIENT
    }

    /** 수신 확인(ACK) 기록: 메시지 잠금 안에서 읽기 → 확인 → 쓰기 (C1). */
    public DeliveryUpdate markDelivered(String id, String userId) throws IOException {
        if (!isValidMessageId(id)) {
            return DeliveryUpdate.NOT_RECIPIENT;
        }
        synchronized (locks.lockFor(id)) {
            Path file = fileOf(id);
            if (!Files.exists(file)) {
                return DeliveryUpdate.NOT_RECIPIENT;
            }
            StoredMessage m = StoredMessage.from(HeaderBodyFile.read(file));
            if (!m.isRecipient(userId)) {
                return DeliveryUpdate.NOT_RECIPIENT;
            }
            if (m.isDelivered(userId)) {
                return DeliveryUpdate.ALREADY;
            }
            m.markDelivered(userId);
            m.getFile().write(file);
            return DeliveryUpdate.CHANGED;
        }
    }

    /** 모든 메시지 ID (색인 다시 만들기용, 3.8.6). */
    public List<String> listIds() throws IOException {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return ids;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String id = name.substring(0, name.length() - 4);
                if (isValidMessageId(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private Path fileOf(String id) {
        return dir.resolve(id + ".txt");
    }

    private static void requireValidId(String id) throws FormatException {
        if (!isValidMessageId(id)) {
            throw new FormatException("messageId가 UUID 형식이 아닙니다: " + id);
        }
    }
}
