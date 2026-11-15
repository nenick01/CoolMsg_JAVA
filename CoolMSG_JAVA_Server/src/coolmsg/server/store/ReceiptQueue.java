package coolmsg.server.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import coolmsg.common.model.DeliveryState;
import coolmsg.common.model.MessageData;

/**
 * 보낸 사람이 오프라인일 때 생긴 수신·읽음 알림 data/receipts/{보낸 사람}.txt (설계서 2.6, 3.1).
 *
 * 한 줄: messageId|userId|state|at. 보낸 사람이 로그인하면 꺼내서 보낸다.
 *
 * 동시성 규칙 C4: "추가"와 "꺼내고 비우기"를 같은 아이디별 잠금 안에서 한다.
 * 꺼내는 순간 파일을 지우므로, 보내는 동안 새로 추가된 알림은 새 파일에 들어가 같이 지워지지 않는다.
 * 보내다 연결이 끊기면 못 보낸 것을 다시 추가한다(addAll).
 */
public class ReceiptQueue {

    /** 알림 하나. */
    public static final class Receipt {
        private final String messageId;
        private final String userId;
        private final DeliveryState state;
        private final String at;

        public Receipt(String messageId, String userId, DeliveryState state, String at) {
            this.messageId = messageId;
            this.userId = userId;
            this.state = state;
            this.at = at;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getUserId() {
            return userId;
        }

        public DeliveryState getState() {
            return state;
        }

        public String getAt() {
            return at;
        }

        String toLine() {
            return messageId + "|" + userId + "|" + state.name() + "|" + at;
        }

        /** 한 줄을 읽는다. 형식이 틀리면(반쯤 쓴 줄 등) null. */
        static Receipt parse(String line) {
            String[] p = line.strip().split("\\|", -1);
            if (p.length != 4 || !MessageData.isValidMessageId(p[0]) || p[1].isEmpty()) {
                return null;
            }
            DeliveryState state = DeliveryState.fromName(p[2]);
            return state == null ? null : new Receipt(p[0], p[1], state, p[3]);
        }
    }

    private final Path dir;
    private final LockStripes locks = new LockStripes();

    public ReceiptQueue(Path dir) {
        this.dir = dir;
    }

    /** 보낸 사람의 대기 파일 끝에 덧붙인다. */
    public void add(String senderId, Receipt r) throws IOException {
        addAll(senderId, List.of(r));
    }

    public void addAll(String senderId, List<Receipt> receipts) throws IOException {
        if (receipts.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Receipt r : receipts) {
            sb.append(r.toLine()).append('\n');
        }
        synchronized (locks.lockFor(senderId)) {
            Files.createDirectories(dir);
            Files.write(fileOf(senderId), sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /** 꺼내고 비운다 (C4). 쌓인 순서대로. */
    public List<Receipt> drain(String senderId) throws IOException {
        List<Receipt> list = new ArrayList<>();
        synchronized (locks.lockFor(senderId)) {
            Path file = fileOf(senderId);
            if (!Files.exists(file)) {
                return list;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Receipt r = Receipt.parse(line);
                if (r != null) {
                    list.add(r);
                }
            }
            Files.delete(file);
        }
        return list;
    }

    private Path fileOf(String senderId) {
        return dir.resolve(senderId + ".txt");
    }
}
