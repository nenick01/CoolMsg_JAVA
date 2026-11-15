package coolmsg.client.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

import javax.swing.JViewport;

/**
 * 폭이 모자라면 다음 줄로 넘기는 FlowLayout.
 *
 * FlowLayout은 그릴 때는 줄을 바꾸지만, "필요한 크기"(preferredSize)는 항상 한 줄로 계산한다.
 * 그래서 BorderLayout·스크롤 창 안에 넣으면 높이가 한 줄로 고정돼, 넘친 항목이 잘려 보이지 않는다.
 * 이 클래스는 지금 폭을 기준으로 몇 줄이 필요한지 계산해 높이를 알려 준다.
 * (작성 창의 받는 사람 칸, 설계서 4.3)
 */
public class WrapLayout extends FlowLayout {

    private static final long serialVersionUID = 1L;

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension d = layoutSize(target, false);
        d.width -= getHgap() + 1;
        return d;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            // 스크롤 창 안이면 보이는 영역(viewport)의 폭으로 계산한다. 패널 자신의 폭은
            // 바뀌기 전 값일 수 있어서, 그걸로 계산하면 줄 수가 한 박자 늦어 아래 줄이 잘린다.
            Container parent = target.getParent();
            int targetWidth = (parent instanceof JViewport) ? parent.getWidth() : target.getWidth();
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE; // 아직 크기가 정해지지 않음 → 한 줄로 계산
            }
            Insets insets = target.getInsets();
            int maxWidth = targetWidth - (insets.left + insets.right + getHgap() * 2);

            int width = 0;
            int height = 0;
            int rowWidth = 0;
            int rowHeight = 0;
            for (Component c : target.getComponents()) {
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                if (rowWidth > 0 && rowWidth + getHgap() + d.width > maxWidth) { // 이 줄에 안 들어감 → 줄 바꿈
                    width = Math.max(width, rowWidth);
                    height += rowHeight + getVgap();
                    rowWidth = 0;
                    rowHeight = 0;
                }
                rowWidth += (rowWidth > 0 ? getHgap() : 0) + d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            width = Math.max(width, rowWidth);
            height += rowHeight;
            return new Dimension(width + insets.left + insets.right + getHgap() * 2,
                    height + insets.top + insets.bottom + getVgap() * 2);
        }
    }
}
