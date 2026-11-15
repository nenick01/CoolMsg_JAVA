package coolmsg.server.store;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import coolmsg.common.FormatException;
import coolmsg.common.file.HeaderBodyFile;
import coolmsg.common.model.MessageData;

/**
 * 서버 메시지 파일 한 개 = 공용 MessageData + 받는 사람별 전달 여부 (설계서 3.2, 3.8.4).
 *
 * MessageData는 Common 공용이라 서버 전용 정보(delivered.*, readAt.*)를 넣지 않는다.
 * 서버 전용 정보는 이 클래스가 HeaderBodyFile의 키로 함께 들고 있다.
 * 읽은 시각 readAt.{아이디}는 읽었을 때만 줄이 생긴다(5단계).
 */
public class StoredMessage {

    public static final String KEY_DELIVERED_PREFIX = "delivered.";
    public static final String KEY_READ_AT_PREFIX = "readAt.";

    private final MessageData data;
    private final HeaderBodyFile file;

    private StoredMessage(MessageData data, HeaderBodyFile file) {
        this.data = data;
        this.file = file;
    }

    /** 새 메시지: 받는 사람·참조 대상 모두 delivered=false로 시작한다. */
    static StoredMessage create(MessageData data) {
        HeaderBodyFile file = new HeaderBodyFile(data.toHeader(), data.getBody());
        StoredMessage m = new StoredMessage(data, file);
        for (String id : m.recipients()) {
            file.put(KEY_DELIVERED_PREFIX + id, "false");
        }
        return m;
    }

    /** 파일 내용에서 읽는다. */
    static StoredMessage from(HeaderBodyFile file) throws FormatException {
        return new StoredMessage(MessageData.fromHeader(file.getHeader(), file.getBody()), file);
    }

    public MessageData getData() {
        return data;
    }

    HeaderBodyFile getFile() {
        return file;
    }

    /** 받는 사람 + 참조 대상 (중복 없이, 순서 유지). 이 사람들에게 전달한다. */
    public List<String> recipients() {
        Set<String> all = new LinkedHashSet<>(data.getReceivers());
        all.addAll(data.getCc());
        return new ArrayList<>(all);
    }

    public boolean isRecipient(String userId) {
        return data.getReceivers().contains(userId) || data.getCc().contains(userId);
    }

    public boolean isDelivered(String userId) {
        return "true".equals(file.get(KEY_DELIVERED_PREFIX + userId));
    }

    void markDelivered(String userId) {
        file.put(KEY_DELIVERED_PREFIX + userId, "true");
    }

    /** 읽은 시각 (서버가 READ를 받은 시각, U49). 안 읽었으면 null. */
    public String getReadAt(String userId) {
        return file.get(KEY_READ_AT_PREFIX + userId);
    }

    void markRead(String userId, String at) {
        file.put(KEY_READ_AT_PREFIX + userId, at);
    }
}
