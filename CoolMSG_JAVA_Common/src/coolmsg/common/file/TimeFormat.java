package coolmsg.common.file;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;

import coolmsg.common.FormatException;

/**
 * 시각 문자열 형식을 한 곳에서 정한다 (설계서 5장 U2).
 * 서버 PC 현지 시각, "yyyy-MM-ddTHH:mm:ss" (초 단위). 예: 2026-11-12T15:30:12
 */
public final class TimeFormat {

    // 'uuuu'(연도)를 써야 STRICT 모드에서 연도를 제대로 읽는다. ('yyyy'는 연호 기준 연도라 STRICT에서 실패)
    // STRICT: 2026-02-30 같은 없는 날짜를 가까운 날짜로 바꾸지 않고 오류로 본다.
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private TimeFormat() {
    }

    /** 시각을 문자열로. 초 아래(밀리초 등)는 버린다. */
    public static String format(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.SECONDS).format(FORMATTER);
    }

    /** 문자열을 시각으로. 형식이 틀리거나 없는 날짜면 FormatException. */
    public static LocalDateTime parse(String text) throws FormatException {
        try {
            return LocalDateTime.parse(text, FORMATTER);
        } catch (DateTimeParseException e) {
            throw new FormatException("시각 형식이 아닙니다 (yyyy-MM-ddTHH:mm:ss): " + text, e);
        }
    }

    /** 지금 시각 (초 단위로 자른 값). */
    public static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
