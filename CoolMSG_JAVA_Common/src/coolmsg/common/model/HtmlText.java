package coolmsg.common.model;

/**
 * 메시지 본문(HTML) 도우미 (설계서 5장 U37).
 *
 * 본문은 4단계부터 항상 HTML로 저장·전송한다. 서식 기능(7단계)이 생겨도 저장 형식이 같아 보기 코드가 하나로 유지된다.
 * - 작성: 일반 텍스트 → HTML (특수 문자 바꾸기, 줄바꿈 → &lt;br&gt;)
 * - 미리보기·빈 본문 검사: HTML → 글자만
 * 서버(빈 본문 검사)와 클라이언트(작성, 목록 미리보기)가 같은 규칙을 쓰도록 Common에 둔다.
 */
public final class HtmlText {

    private HtmlText() {
    }

    /** 일반 텍스트를 HTML로: &amp; &lt; &gt; &quot; 를 바꾸고 줄바꿈을 &lt;br&gt;로. */
    public static String fromPlain(String plain) {
        StringBuilder sb = new StringBuilder(plain.length() + 16);
        String text = plain.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\n' -> sb.append("<br>\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** HTML에서 글자만 꺼낸다: &lt;br&gt;은 줄바꿈, 다른 태그는 지우고, 기본 문자 참조를 되돌린다. */
    public static String toPlain(String html) {
        String s = html.replace("\r", "")
                .replaceAll("(?i)<br\\s*/?>\\n?", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]*>", "");
        return s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&"); // &amp;는 마지막에 (먼저 바꾸면 &amp;lt; 가 < 로 잘못 바뀐다)
    }

    /** 태그를 뺀 글자가 공백뿐인가 (빈 본문 검사, U5). */
    public static boolean isBlank(String html) {
        return toPlain(html).isBlank();
    }

    /** 목록 미리보기: 글자만, 줄바꿈은 공백으로, 최대 maxChars자(넘으면 …). */
    public static String preview(String html, int maxChars) {
        String plain = toPlain(html).replaceAll("\\s+", " ").strip();
        return plain.length() <= maxChars ? plain : plain.substring(0, maxChars) + "…";
    }
}
