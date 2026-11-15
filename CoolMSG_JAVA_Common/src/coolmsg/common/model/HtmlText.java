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
        return decodeAllNumericRefs(s).replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&"); // &amp;는 마지막에 (먼저 바꾸면 &amp;lt; 가 < 로 잘못 바뀐다)
    }

    private static final java.util.regex.Pattern NUMERIC_REF = java.util.regex.Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /**
     * 숫자 문자 참조(&amp;#44032; 등)를 글자로 되돌린다. 단 HTML에서 뜻이 있는 &lt; &gt; &amp; &quot;는 그대로 둔다.
     * Swing 편집기는 한글을 숫자 참조로 써서 본문이 몇 배로 커지고 미리보기에 그대로 보이기 때문이다.
     */
    public static String decodeNumericRefs(String html) {
        return replaceNumericRefs(html, true);
    }

    private static String decodeAllNumericRefs(String text) {
        return replaceNumericRefs(text, false);
    }

    private static String replaceNumericRefs(String s, boolean keepSpecial) {
        java.util.regex.Matcher m = NUMERIC_REF.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = m.group();
            try {
                int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                boolean special = code == '<' || code == '>' || code == '&' || code == '"';
                if (Character.isValidCodePoint(code) && !(keepSpecial && special)) {
                    replacement = new String(Character.toChars(code));
                }
            } catch (NumberFormatException e) {
                // 너무 큰 수: 그대로 둔다
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 태그를 뺀 글자가 공백뿐인가 (빈 본문 검사, U5). */
    public static boolean isBlank(String html) {
        return toPlain(html).isBlank();
    }

    // =====================================================================
    // 정화 (설계서 4.3, 5장 U59)
    // =====================================================================

    /** 남겨 두는 서식 태그. 그 밖의 태그는 지우고 안의 글자만 남긴다. */
    private static final java.util.Set<String> ALLOWED_TAGS = java.util.Set.of(
            "b", "strong", "i", "em", "u", "s", "strike", "font", "span", "br", "p", "div", "hr", "blockquote");
    /** 안의 글자까지 지우는 태그. */
    private static final java.util.Set<String> DROP_WITH_CONTENT = java.util.Set.of("script", "style", "head", "title");
    /** style 속성에서 남기는 항목. */
    private static final java.util.Set<String> ALLOWED_CSS = java.util.Set.of(
            "color", "font-size", "font-family", "font-weight", "font-style", "text-decoration");
    private static final java.util.regex.Pattern TAG =
            java.util.regex.Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|<!--.*?-->", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern ATTR = java.util.regex.Pattern.compile(
            "([a-zA-Z-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");

    /**
     * 허용한 서식 태그만 남긴다 (U59). 보여 주기 전과 보내기 전에 부른다.
     * - 허용: b strong i em u s strike font(face·size·color) span(style 일부) br p div hr blockquote
     * - script·style·head·title은 안의 글자까지 지운다. 나머지 태그(img·a·iframe·html·body 등)는 태그만 지운다.
     * - 속성은 font의 face·size·color, 그리고 style의 색·글꼴·크기·굵기·기울임·밑줄만 남긴다(url(·expression 금지).
     */
    public static String sanitize(String html) {
        StringBuilder out = new StringBuilder(html.length());
        java.util.regex.Matcher m = TAG.matcher(html);
        int last = 0;
        String dropUntil = null; // 이 태그가 닫힐 때까지 글자도 버림
        while (m.find()) {
            if (dropUntil == null) {
                out.append(html, last, m.start());
            }
            last = m.end();
            if (m.group(2) == null) {
                continue; // 주석
            }
            boolean closing = !m.group(1).isEmpty();
            String name = m.group(2).toLowerCase(java.util.Locale.ROOT);
            if (dropUntil != null) {
                if (closing && name.equals(dropUntil)) {
                    dropUntil = null;
                }
                continue;
            }
            if (DROP_WITH_CONTENT.contains(name)) {
                if (!closing && !m.group(3).trim().endsWith("/")) {
                    dropUntil = name;
                }
                continue;
            }
            if (!ALLOWED_TAGS.contains(name)) {
                continue;
            }
            if (closing) {
                out.append("</").append(name).append('>');
            } else {
                out.append('<').append(name).append(attributes(name, m.group(3))).append('>');
            }
        }
        if (dropUntil == null) {
            out.append(html.substring(last));
        }
        return out.toString();
    }

    private static String attributes(String tag, String raw) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher a = ATTR.matcher(raw);
        while (a.find()) {
            String key = a.group(1).toLowerCase(java.util.Locale.ROOT);
            String value = a.group(3) != null ? a.group(3) : a.group(4) != null ? a.group(4) : a.group(5);
            String kept = null;
            if (tag.equals("font") && (key.equals("face") || key.equals("size") || key.equals("color"))) {
                kept = safeValue(value);
            } else if (key.equals("style")) {
                kept = safeStyle(value);
            }
            if (kept != null && !kept.isEmpty()) {
                sb.append(' ').append(key).append("=\"").append(kept).append('"');
            }
        }
        return sb.toString();
    }

    private static String safeStyle(String style) {
        StringBuilder sb = new StringBuilder();
        for (String decl : style.split(";")) {
            int colon = decl.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String prop = decl.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            String value = safeValue(decl.substring(colon + 1).trim());
            if (ALLOWED_CSS.contains(prop) && value != null && !value.isEmpty()) {
                sb.append(sb.length() == 0 ? "" : "; ").append(prop).append(": ").append(value);
            }
        }
        return sb.toString();
    }

    /** 속성 값: 따옴표·꺾쇠·url(·expression이 있으면 버린다. */
    private static String safeValue(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("\"") || value.contains("<") || value.contains(">") || lower.contains("url(")
                || lower.contains("expression") || lower.contains("javascript:")) {
            return null;
        }
        return value.trim();
    }

    /** 목록 미리보기: 글자만, 줄바꿈은 공백으로, 최대 maxChars자(넘으면 …). */
    public static String preview(String html, int maxChars) {
        String plain = toPlain(html).replaceAll("\\s+", " ").strip();
        return plain.length() <= maxChars ? plain : plain.substring(0, maxChars) + "…";
    }
}
