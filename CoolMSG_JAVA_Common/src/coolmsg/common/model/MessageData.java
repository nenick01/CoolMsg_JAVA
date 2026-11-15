package coolmsg.common.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import coolmsg.common.FormatException;
import coolmsg.common.file.TimeFormat;

/**
 * 메시지 하나의 공통 모양 (설계서 1.2, 3.2).
 *
 * 서버의 메시지 파일, 클라이언트의 보관함 파일, SEND/MSG 패킷이 모두 이 모양을 쓴다.
 * 서버 전용 정보(delivered.*, readAt.*)와 클라이언트 전용 정보(read, state.*)는 여기에 넣지 않고,
 * 각자 HeaderBodyFile에 키를 덧붙여 쓴다.
 *
 * 헤더로 바꾸기: toHeader()  /  헤더에서 읽기: fromHeader(header, body)
 */
public class MessageData {

    public static final String KEY_MESSAGE_ID = "messageId";
    public static final String KEY_SENDER = "sender";
    public static final String KEY_RECEIVERS = "receivers";
    public static final String KEY_CC = "cc";
    public static final String KEY_SENT_AT = "sentAt";
    public static final String KEY_REPLY_TO = "replyTo";
    public static final String KEY_FORWARD_OF = "forwardOf";
    public static final String KEY_ATTACHMENT_PREFIX = "attachment.";

    private final String messageId;
    private final List<String> receivers;
    private final List<String> cc;
    private final String body;

    private String sender;          // SEND에는 없고, 서버가 채운다
    private LocalDateTime sentAt;   // SEND에는 없고, 서버가 채운다
    private String replyTo;
    private String forwardOf;
    private List<AttachmentInfo> attachments = Collections.emptyList();

    public MessageData(String messageId, List<String> receivers, List<String> cc, String body) {
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId가 비어 있습니다.");
        }
        if (receivers == null || cc == null || body == null) {
            throw new IllegalArgumentException("받는 사람·참조·본문은 null일 수 없습니다.");
        }
        checkIds(receivers);
        checkIds(cc);
        this.messageId = messageId;
        this.receivers = List.copyOf(receivers); // 밖에서 목록을 고쳐도 영향 없게 복사 (읽기 전용)
        this.cc = List.copyOf(cc);
        this.body = body;
    }

    // ---------- 헤더 변환 ----------

    /** 헤더 맵으로 바꾼다. 값이 없는 선택 항목은 줄을 만들지 않는다. */
    public Map<String, String> toHeader() {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put(KEY_MESSAGE_ID, messageId);
        if (sender != null) {
            h.put(KEY_SENDER, sender);
        }
        h.put(KEY_RECEIVERS, String.join(",", receivers));
        if (!cc.isEmpty()) {
            h.put(KEY_CC, String.join(",", cc));
        }
        if (sentAt != null) {
            h.put(KEY_SENT_AT, TimeFormat.format(sentAt));
        }
        if (replyTo != null) {
            h.put(KEY_REPLY_TO, replyTo);
        }
        if (forwardOf != null) {
            h.put(KEY_FORWARD_OF, forwardOf);
        }
        for (int i = 0; i < attachments.size(); i++) {
            h.put(KEY_ATTACHMENT_PREFIX + i, attachments.get(i).toHeaderValue());
        }
        return h;
    }

    /**
     * 헤더 맵과 본문에서 읽는다. 모르는 키(delivered.* 등)는 무시한다.
     * messageId와 receivers는 반드시 있어야 한다.
     */
    public static MessageData fromHeader(Map<String, String> h, String body) throws FormatException {
        String messageId = h.get(KEY_MESSAGE_ID);
        if (messageId == null || messageId.isEmpty()) {
            throw new FormatException("messageId가 없습니다.");
        }
        if (!h.containsKey(KEY_RECEIVERS)) {
            throw new FormatException("receivers가 없습니다.");
        }
        MessageData m;
        try {
            m = new MessageData(messageId, splitIds(h.get(KEY_RECEIVERS)), splitIds(h.get(KEY_CC)), body);
        } catch (IllegalArgumentException e) {
            throw new FormatException(e.getMessage(), e);
        }
        m.sender = h.get(KEY_SENDER);
        if (h.containsKey(KEY_SENT_AT)) {
            m.sentAt = TimeFormat.parse(h.get(KEY_SENT_AT));
        }
        m.replyTo = h.get(KEY_REPLY_TO);
        m.forwardOf = h.get(KEY_FORWARD_OF);
        List<AttachmentInfo> list = new ArrayList<>();
        for (int i = 0; h.containsKey(KEY_ATTACHMENT_PREFIX + i); i++) {
            list.add(AttachmentInfo.parse(h.get(KEY_ATTACHMENT_PREFIX + i)));
        }
        m.attachments = Collections.unmodifiableList(list);
        return m;
    }

    // ---------- 쉼표 목록 ----------

    private static List<String> splitIds(String value) {
        List<String> ids = new ArrayList<>();
        if (value != null) {
            for (String id : value.split(",")) {
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static void checkIds(List<String> ids) {
        for (String id : ids) {
            if (id == null || id.isEmpty() || id.indexOf(',') >= 0) {
                throw new IllegalArgumentException("아이디가 비었거나 쉼표가 있습니다.");
            }
        }
    }

    // ---------- getter / setter ----------

    public String getMessageId() {
        return messageId;
    }

    public List<String> getReceivers() {
        return receivers;
    }

    public List<String> getCc() {
        return cc;
    }

    public String getBody() {
        return body;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }

    public String getForwardOf() {
        return forwardOf;
    }

    public void setForwardOf(String forwardOf) {
        this.forwardOf = forwardOf;
    }

    public List<AttachmentInfo> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<AttachmentInfo> attachments) {
        this.attachments = List.copyOf(attachments);
    }
}
