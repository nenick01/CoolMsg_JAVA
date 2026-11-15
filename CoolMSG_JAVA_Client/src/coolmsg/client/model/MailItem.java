package coolmsg.client.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import coolmsg.common.model.MessageData;

/**
 * 보관함의 메시지 하나 (받은 내역 또는 보낸 내역, 설계서 4.2).
 *
 * MessageData(공용 모양)에 클라이언트 전용 정보(받은 메시지의 읽음 여부)를 더한 것.
 * 화면 목록은 이 객체의 목록을 보여 준다.
 */
public final class MailItem {

    /** 목록에 보여 줄 날짜·시간 형식 (U42). */
    public static final DateTimeFormatter LIST_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");

    private final MessageData data;
    private final boolean read;

    public MailItem(MessageData data, boolean read) {
        this.data = data;
        this.read = read;
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
