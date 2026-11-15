package coolmsg.client.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import coolmsg.common.FormatException;
import coolmsg.common.file.TimeFormat;
import coolmsg.common.model.DeliveryState;
import coolmsg.common.model.MessageData;

/**
 * 보관함의 메시지 하나 (받은 내역 또는 보낸 내역, 설계서 4.2).
 *
 * MessageData(공용 모양)에 클라이언트 전용 정보를 더한 것:
 *   받은 메시지 — 읽음 여부, 보낸 메시지 — 받는 사람별 상태(state.{아이디}, readAt.{아이디}, deletedUser.{아이디}, 5단계).
 * 화면 목록은 이 객체의 목록을 보여 준다.
 */
public final class MailItem {

    /** 목록에 보여 줄 날짜·시간 형식 (U42). */
    public static final DateTimeFormatter LIST_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    public static final String KEY_STATE_PREFIX = "state.";
    public static final String KEY_READ_AT_PREFIX = "readAt.";
    public static final String KEY_DELETED_USER_PREFIX = "deletedUser.";

    private final MessageData data;
    private final boolean read;
    /** 보관함 파일의 머리글 전체 (보낸 메시지의 받는 사람별 상태를 여기서 읽는다). */
    private final Map<String, String> header;

    public MailItem(MessageData data, boolean read) {
        this(data, read, Map.of());
    }

    public MailItem(MessageData data, boolean read, Map<String, String> header) {
        this.data = data;
        this.read = read;
        this.header = header;
    }

    /** 받는 사람 + 참조 (중복 없이, 순서 유지). */
    public List<String> recipients() {
        Set<String> all = new LinkedHashSet<>(data.getReceivers());
        all.addAll(data.getCc());
        return new ArrayList<>(all);
    }

    /** 보낸 메시지: 그 사람의 상태. 기록이 없으면 SENT. */
    public DeliveryState stateOf(String userId) {
        DeliveryState s = DeliveryState.fromName(header.get(KEY_STATE_PREFIX + userId));
        return s == null ? DeliveryState.SENT : s;
    }

    /** 보낸 메시지: 읽은 시각 "2026-11-23 15:42". 없으면 빈 문자열. */
    public String readAtText(String userId) {
        String at = header.get(KEY_READ_AT_PREFIX + userId);
        if (at == null) {
            return "";
        }
        try {
            return LIST_TIME.format(TimeFormat.parse(at));
        } catch (FormatException e) {
            return at;
        }
    }

    /** 보낸 메시지: 서버가 STATUS에서 삭제된 사용자라고 알려 줬는가 (U4). */
    public boolean isDeletedUser(String userId) {
        return "true".equals(header.get(KEY_DELETED_USER_PREFIX + userId));
    }

    /** 보낸 메시지 목록의 읽음 요약 "읽은 수/받는 사람 수". 삭제된 사용자는 분모에서 뺀다 (U4). */
    public String readSummary() {
        int total = 0;
        int readCount = 0;
        for (String id : recipients()) {
            if (isDeletedUser(id)) {
                continue;
            }
            total++;
            if (stateOf(id) == DeliveryState.READ) {
                readCount++;
            }
        }
        return readCount + "/" + total;
    }

    public MessageData getData() {
        return data;
    }

    public String getMessageId() {
        return data.getMessageId();
    }

    /** 받은 메시지: 읽었는가. 보낸 메시지는 항상 true. */
    public boolean isRead() {
        return read;
    }

    public LocalDateTime getSentAt() {
        return data.getSentAt();
    }

    /** "2026-11-23 15:30". 시각이 없으면 빈 문자열. */
    public String sentAtText() {
        return data.getSentAt() == null ? "" : LIST_TIME.format(data.getSentAt());
    }
}
