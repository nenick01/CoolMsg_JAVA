package coolmsg.client.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.html.CSS;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import coolmsg.common.model.HtmlText;

/**
 * 서식 있는 본문 편집기 (설계서 4.3, 5장 U58).
 *
 * [글꼴▾] [크기▾] [B] [I] [U] [S] [A▾] 도구 + JTextPane(HTMLEditorKit).
 * - 고른 글자에 적용하고, 고른 글자가 없으면 이후 입력하는 글자에 적용한다(StyledEditorKit의 동작).
 * - getHtml(): 편집기 HTML에서 body 안쪽만 꺼내 정화(U59)한 본문.
 * - 글꼴은 받는 사람 PC에도 있는 Windows 한글판 기본 글꼴만, 크기는 1~7단계(8~36pt).
 */
public class RichTextEditor extends JPanel {

    private static final long serialVersionUID = 1L;

    public static final String[] FONTS = {"맑은 고딕", "굴림", "돋움", "바탕", "궁서"};
    /** 실제로 적용하는 글꼴 이름(영문). 한글 이름은 HTML로 쓸 때 사라져서(face="") 영문 이름을 쓴다. */
    public static final String[] FONT_FAMILIES = {"Malgun Gothic", "Gulim", "Dotum", "Batang", "Gungsuh"};
    /** 크기 1~7단계의 pt (HTML font size 1~7과 같은 단계). 기본 3. */
    public static final int[] SIZES = {8, 10, 12, 14, 18, 24, 36};
    public static final int DEFAULT_SIZE_INDEX = 2;
    /** 글자 색 16색. */
    public static final Color[] COLORS = {
        Color.BLACK, new Color(0x80, 0x80, 0x80), new Color(0xC0, 0xC0, 0xC0), Color.WHITE,
        new Color(0xC0, 0x00, 0x00), new Color(0xFF, 0x00, 0x00), new Color(0xFF, 0x80, 0x00), new Color(0xFF, 0xC0, 0x00),
        new Color(0x00, 0x80, 0x00), new Color(0x00, 0xB0, 0x50), new Color(0x00, 0x80, 0x80), new Color(0x00, 0xB0, 0xF0),
        new Color(0x00, 0x00, 0xFF), new Color(0x00, 0x20, 0x60), new Color(0x70, 0x30, 0xA0), new Color(0xFF, 0x00, 0xFF)};

    /** 문단 여백 없음. 편집기와 받은 메시지 표시가 같은 규칙을 써야 줄 간격이 같다. */
    public static final String PARAGRAPH_RULE = "p { margin-top: 0; margin-bottom: 0; }";

    private static final Pattern BODY = Pattern.compile("<body[^>]*>(.*)</body>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final JTextPane pane = new JTextPane();

    public RichTextEditor() {
        super(new BorderLayout());
        HTMLEditorKit kit = new HTMLEditorKit();
        pane.setEditorKit(kit);
        pane.setDocument(kit.createDefaultDocument());
        ((HTMLDocument) pane.getDocument()).getStyleSheet().addRule(
                "body { font-family: '" + FONT_FAMILIES[0] + "'; font-size: " + SIZES[DEFAULT_SIZE_INDEX] + "pt; margin: 6px; }");
        // 문단 사이 기본 여백(15)을 없앤다: Enter 한 번 = 한 줄 (받는 쪽 표시도 같은 규칙, MessageManagerFrame)
        ((HTMLDocument) pane.getDocument()).getStyleSheet().addRule(PARAGRAPH_RULE);
        pane.setMargin(new Insets(4, 4, 4, 4));

        JComboBox<String> font = new JComboBox<>(FONTS);
        font.setToolTipText("글꼴");
        font.addActionListener(e -> {
            applyFontFamily(FONT_FAMILIES[font.getSelectedIndex()]);
            pane.requestFocusInWindow();
        });
        String[] sizeLabels = new String[SIZES.length];
        for (int i = 0; i < SIZES.length; i++) {
            sizeLabels[i] = (i + 1) + " (" + SIZES[i] + "pt)";
        }
        JComboBox<String> size = new JComboBox<>(sizeLabels);
        size.setSelectedIndex(DEFAULT_SIZE_INDEX);
        size.setToolTipText("글자 크기");
        size.addActionListener(e -> {
            int pt = SIZES[size.getSelectedIndex()];
            new StyledEditorKit.FontSizeAction(pt + "pt", pt)
                    .actionPerformed(new java.awt.event.ActionEvent(pane, e.getID(), Integer.toString(pt)));
            pane.requestFocusInWindow();
        });

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        tools.add(font);
        tools.add(size);
        tools.add(styleButton("B", Font.BOLD, "굵게", new StyledEditorKit.BoldAction()));
        tools.add(styleButton("I", Font.ITALIC, "기울임", new StyledEditorKit.ItalicAction()));
        tools.add(styleButton("U", Font.PLAIN, "밑줄", new StyledEditorKit.UnderlineAction()));
        JButton strike = styleButton("S", Font.PLAIN, "취소선", null);
        strike.addActionListener(e -> toggleStrike());
        tools.add(strike);
        tools.add(colorButton());

        add(tools, BorderLayout.NORTH);
        add(new JScrollPane(pane), BorderLayout.CENTER);
    }

    private JButton styleButton(String text, int style, String tip, javax.swing.Action action) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(style));
        b.setMargin(new Insets(1, 7, 1, 7));
        b.setToolTipText(tip);
        b.setFocusable(false); // 누를 때 편집기의 선택이 풀리지 않게
        if (action != null) {
            b.addActionListener(e -> {
                action.actionPerformed(new java.awt.event.ActionEvent(pane, e.getID(), e.getActionCommand()));
                pane.requestFocusInWindow();
            });
        }
        return b;
    }

    /**
     * 글꼴: 고른 글자(또는 이후 입력)에 적용한다.
     * StyledEditorKit.FontFamilyAction은 HTML로 쓸 때 글꼴 이름을 잃어버린다(JDK의 CSS 변환이 이름을 비움 → face="").
     * 그래서 CSS 속성을 문자열로 직접 넣는다.
     */
    private void applyFontFamily(String family) {
        HTMLDocument doc = (HTMLDocument) pane.getDocument();
        SimpleAttributeSet attr = new SimpleAttributeSet();
        doc.getStyleSheet().addCSSAttribute(attr, CSS.Attribute.FONT_FAMILY, family);
        int start = pane.getSelectionStart();
        int end = pane.getSelectionEnd();
        if (start != end) {
            doc.setCharacterAttributes(start, end - start, attr, false);
        }
        ((StyledEditorKit) pane.getEditorKit()).getInputAttributes().addAttributes(attr);
        pane.requestFocusInWindow();
    }

    /** 취소선: 고른 글자(또는 이후 입력)의 취소선을 켜고 끈다. StyledEditorKit에 기본 동작이 없어 직접 만든다. */
    private void toggleStrike() {
        MutableAttributeSet input = ((StyledEditorKit) pane.getEditorKit()).getInputAttributes();
        boolean on = !StyleConstants.isStrikeThrough(input);
        SimpleAttributeSet attr = new SimpleAttributeSet();
        StyleConstants.setStrikeThrough(attr, on);
        int start = pane.getSelectionStart();
        int end = pane.getSelectionEnd();
        if (start != end) {
            ((HTMLDocument) pane.getDocument()).setCharacterAttributes(start, end - start, attr, false);
        }
        input.addAttributes(attr);
        pane.requestFocusInWindow();
    }

    /** [A▾]: 16색 팔레트. */
    private JButton colorButton() {
        JButton b = new JButton("A▾");
        b.setMargin(new Insets(1, 6, 1, 6));
        b.setToolTipText("글자 색");
        b.setFocusable(false);
        JPopupMenu popup = new JPopupMenu();
        JPanel grid = new JPanel(new GridLayout(4, 4, 2, 2));
        grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        for (Color c : COLORS) {
            JButton swatch = new JButton();
            swatch.setBackground(c);
            swatch.setOpaque(true);
            swatch.setBorder(BorderFactory.createLineBorder(Color.GRAY));
            swatch.setPreferredSize(new Dimension(18, 18));
            swatch.setFocusable(false);
            swatch.addActionListener(e -> {
                new StyledEditorKit.ForegroundAction("color", c)
                        .actionPerformed(new java.awt.event.ActionEvent(pane, e.getID(), ""));
                b.setForeground(c.equals(Color.WHITE) ? Color.GRAY : c);
                popup.setVisible(false);
                pane.requestFocusInWindow();
            });
            grid.add(swatch);
        }
        popup.add(grid);
        b.addActionListener(e -> popup.show(b, 0, b.getHeight()));
        return b;
    }

    // =====================================================================

    /** 본문 HTML (body 안쪽만, 정화함 — U59). */
    public String getHtml() {
        return HtmlText.sanitize(HtmlText.decodeNumericRefs(bodyOf(pane.getText())));
    }

    /**
     * 본문을 채운다 (회신 인용 등). 커서는 맨 위.
     * 빈 본문도 빈 문단(<p>)으로 시작한다: 문단 없이 시작하면 Enter가 HTML에 줄바꿈 문자만 넣어
     * 받는 쪽에서 띄어쓰기로 보인다(문단이 있으면 Enter가 문단을 나눈다).
     */
    public void setHtml(String bodyHtml) {
        String body = bodyHtml == null || bodyHtml.isBlank() ? "<p></p>" : bodyHtml;
        pane.setText("<html><body>" + body + "</body></html>");
        pane.setCaretPosition(0);
    }

    public void setEditable(boolean editable) {
        pane.setEditable(editable);
    }

    public void focusEditor() {
        pane.requestFocusInWindow();
        pane.setCaretPosition(0);
    }

    /** 편집기 HTML 전체에서 body 안쪽만. body가 없으면 그대로. */
    public static String bodyOf(String html) {
        Matcher m = BODY.matcher(html);
        return m.find() ? m.group(1).strip() : html.strip();
    }

    /** 테스트용: 편집기 자체 (서식 적용 확인). */
    JTextPane pane() {
        return pane;
    }

    /** 테스트용: 글꼴 적용. */
    void applyFontFamilyForTest(String family) {
        applyFontFamily(family);
    }
}
