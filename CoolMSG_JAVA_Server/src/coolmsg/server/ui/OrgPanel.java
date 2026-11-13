package coolmsg.server.ui;

import java.awt.BorderLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * [조직도 관리] 탭.
 *
 * 다음 단계에서 왼쪽 부서 트리(JTree), 오른쪽 미배치 사용자 목록, 부서 추가·이름 변경·삭제·순서 이동,
 * 사용자 배치, 저장(org.txt) 기능을 구현한다. 화면 구성은 docs/03_설계서.md 3.5 참고.
 */
public class OrgPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    public OrgPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel label = new JLabel("조직도 관리 화면은 다음 단계에서 구현합니다.", SwingConstants.CENTER);
        add(label, BorderLayout.CENTER);
    }
}
