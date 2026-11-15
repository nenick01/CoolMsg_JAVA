package coolmsg.common.model;

import java.time.LocalDateTime;

import coolmsg.common.FormatException;
import coolmsg.common.file.TimeFormat;

/**
 * 첨부파일 정보 한 개 (설계서 2.3 첨부파일 헤더 형식).
 *
 * 헤더 값 형식: attachmentId|크기|만료시각|파일이름
 * 파일 이름에 '|'나 ','가 있을 수 있어서 파일 이름을 맨 뒤에 둔다.
 * 앞의 세 칸만 '|'로 자르고 나머지는 전부 파일 이름으로 본다.
 *
 * 제출 범위에서 첨부파일은 뼈대만 있으므로, 메시지의 첨부 목록은 항상 비어 있다 (설계서 1.3).
 */
public class AttachmentInfo {

    private final String id;
    private final long size;
    private final LocalDateTime expiresAt;
    private final String fileName;

    public AttachmentInfo(String id, long size, LocalDateTime expiresAt, String fileName) {
        if (id == null || id.isEmpty() || id.indexOf('|') >= 0) {
            throw new IllegalArgumentException("첨부파일 ID가 비었거나 '|'가 있습니다.");
        }
        if (size < 0) {
            throw new IllegalArgumentException("크기는 0 이상이어야 합니다.");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("만료 시각이 없습니다.");
        }
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("파일 이름이 비어 있습니다.");
        }
        this.id = id;
        this.size = size;
        this.expiresAt = expiresAt;
        this.fileName = fileName;
    }

    /** 헤더 값으로 바꾼다: id|size|expiresAt|fileName */
    public String toHeaderValue() {
        return id + "|" + size + "|" + TimeFormat.format(expiresAt) + "|" + fileName;
    }

    /** 헤더 값을 읽는다. */
    public static AttachmentInfo parse(String value) throws FormatException {
        String[] parts = value.split("\\|", 4); // 최대 4칸: 파일 이름 안의 '|'는 자르지 않는다
        if (parts.length != 4) {
            throw new FormatException("첨부파일 정보 형식이 아닙니다: " + value);
        }
        long size;
        try {
            size = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new FormatException("첨부파일 크기가 숫자가 아닙니다: " + parts[1], e);
        }
        try {
            return new AttachmentInfo(parts[0], size, TimeFormat.parse(parts[2]), parts[3]);
        } catch (IllegalArgumentException e) {
            throw new FormatException("첨부파일 정보가 잘못되었습니다: " + e.getMessage(), e);
        }
    }

    public String getId() {
        return id;
    }

    public long getSize() {
        return size;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public String getFileName() {
        return fileName;
    }
}
