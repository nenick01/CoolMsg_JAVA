package coolmsg.client.ui;

/** 테스트에서 RichTextEditor의 글꼴 적용을 부르기 위한 통로 (같은 패키지 메서드를 연결). */
public final class RichTextEditorTestAccess {

    private RichTextEditorTestAccess() {
    }

    public static void applyFont(RichTextEditor editor, String family) {
        editor.applyFontFamilyForTest(family);
    }
}
