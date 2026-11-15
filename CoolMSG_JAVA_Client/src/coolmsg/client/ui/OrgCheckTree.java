package coolmsg.client.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import coolmsg.client.model.UserEntry;
import coolmsg.common.model.OrgTree;

/**
 * 메인 창의 체크박스 조직도 (설계서 2.10, 4.3, 5장 U53).
 *
 * - 부서와 사람 모두 체크 상자가 있다. 계층은 트리 그대로(펼치기·접기).
 * - 부서를 누르면 그 부서와 하위 부서의 사람이 모두 체크된다(나 포함). 다 체크된 부서를 누르면 모두 풀린다.
 * - 부서 상자는 사람들의 상태로 정해진다: 모두 ☑ / 일부 ▣ / 없음 ☐. 일부인 부서를 누르면 모두 체크.
 * - 조직도를 받으면 부서 트리 + 맨 아래 '미배치'(있을 때만), 못 받으면 등록 순서의 한 줄 목록(임시 표시).
 * - 사람 줄은 아무 데나 누르면 체크가 바뀌고, 부서 줄은 상자를 눌러야 바뀐다(이름을 누르면 고르기만).
 * Swing 스레드에서만 쓴다.
 */
public class OrgCheckTree extends JTree {

    private static final long serialVersionUID = 1L;

    public static final String UNASSIGNED = "미배치";
    private static final int BOX = 13;
    private static final int BOX_GAP = 4;

    /** 부서 칸 값. */
    private static final class Dept {
        final String name;

        Dept(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** 사람 칸 값. */
    private static final class Person {
        UserEntry entry;
        boolean checked;

        Person(UserEntry entry) {
            this.entry = entry;
        }

        @Override
        public String toString() {
            return entry.toString(); // "● 김철수 (kim)"
        }
    }

    private enum State {
        NONE, SOME, ALL
    }

    private final DefaultTreeModel model;

    public OrgCheckTree() {
        super(new DefaultTreeModel(new DefaultMutableTreeNode()));
        model = (DefaultTreeModel) getModel();
        setRootVisible(false);
        setShowsRootHandles(true);
        setToggleClickCount(0); // 두 번 눌러도 펼쳐지지 않게 (체크와 헷갈리지 않게). 펼치기는 왼쪽 손잡이로
        setCellRenderer(new Renderer());
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                TreePath path = getPathForLocation(e.getX(), e.getY());
                if (path == null) {
                    return;
                }
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) path.getLastPathComponent();
                Rectangle r = getPathBounds(path);
                boolean onBox = r != null && e.getX() < r.x + BOX + BOX_GAP + 2;
                if (n.getUserObject() instanceof Person || onBox) {
                    toggle(n);
                }
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                TreePath path = getSelectionPath();
                if (e.getKeyCode() == KeyEvent.VK_SPACE && path != null) {
                    toggle((DefaultMutableTreeNode) path.getLastPathComponent());
                }
            }
        });
    }

    // =====================================================================
    // 내용 채우기
    // =====================================================================

    /** 조직도대로 (ORG_OK). users는 LOGIN_OK의 목록(등록 순서): 조직도에 없는 사람은 '미배치'로, 목록에 없는 아이디는 건너뜀(U50). */
    public void showOrg(OrgTree org, List<UserEntry> users) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        Set<String> placed = new HashSet<>();
        if (org.getRoot() != null) {
            root.add(toNode(org.getRoot(), users, placed));
        }
        DefaultMutableTreeNode unassigned = new DefaultMutableTreeNode(new Dept(UNASSIGNED));
        for (UserEntry u : users) {
            if (!placed.contains(u.getId())) {
                unassigned.add(new DefaultMutableTreeNode(new Person(u), false));
            }
        }
        if (unassigned.getChildCount() > 0) {
            root.add(unassigned);
        }
        setContent(root);
    }

    /** 임시 표시 (ORG_NONE, 시간 초과, 형식 오류): 등록 순서의 한 줄 목록. */
    public void showFlat(List<UserEntry> users) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        for (UserEntry u : users) {
            root.add(new DefaultMutableTreeNode(new Person(u), false));
        }
        setContent(root);
    }

    private DefaultMutableTreeNode toNode(OrgTree.Node dept, List<UserEntry> users, Set<String> placed) {
        DefaultMutableTreeNode t = new DefaultMutableTreeNode(new Dept(dept.getName()));
        for (OrgTree.Node c : dept.getChildren()) {
            if (c.isDept()) {
                t.add(toNode(c, users, placed));
            } else {
                UserEntry u = find(users, c.getName());
                if (u != null && placed.add(u.getId())) {
                    t.add(new DefaultMutableTreeNode(new Person(u), false));
                }
            }
        }
        return t;
    }

    private static UserEntry find(List<UserEntry> users, String id) {
        for (UserEntry u : users) {
            if (u.getId().equals(id)) {
                return u;
            }
        }
        return null;
    }

    private void setContent(DefaultMutableTreeNode root) {
        model.setRoot(root);
        for (int i = 0; i < getRowCount(); i++) {
            expandRow(i); // 처음에는 모두 펼침
        }
    }

    // =====================================================================
    // 체크
    // =====================================================================

    private void toggle(DefaultMutableTreeNode n) {
        if (n.getUserObject() instanceof Person) {
            Person p = (Person) n.getUserObject();
            p.checked = !p.checked;
        } else {
            boolean check = stateOf(n) != State.ALL; // 모두 → 모두 풀기, 일부·없음 → 모두 체크
            for (Person p : people(n)) {
                p.checked = check;
            }
        }
        repaint(); // 부모 부서들의 상태도 바뀌므로 전체를 다시 그린다
    }

    private static List<Person> people(DefaultMutableTreeNode n) {
        List<Person> list = new ArrayList<>();
        Enumeration<TreeNode> e = n.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            Object o = ((DefaultMutableTreeNode) e.nextElement()).getUserObject();
            if (o instanceof Person) {
                list.add((Person) o);
            }
        }
        return list;
    }

    private static State stateOf(DefaultMutableTreeNode n) {
        List<Person> ps = people(n);
        int checked = 0;
        for (Person p : ps) {
            if (p.checked) {
                checked++;
            }
        }
        if (checked == 0) {
            return State.NONE;
        }
        return checked == ps.size() ? State.ALL : State.SOME;
    }

    /** 부서 이름 또는 사용자 아이디로 칸을 찾아 누른다 (처음 찾은 칸). 없으면 false. */
    public boolean toggle(String deptNameOrUserId) {
        DefaultMutableTreeNode n = findNode(deptNameOrUserId);
        if (n == null) {
            return false;
        }
        toggle(n);
        return true;
    }

    /** 부서 상자의 상태: "ALL" / "SOME" / "NONE". 없는 부서면 null. */
    public String deptState(String deptName) {
        DefaultMutableTreeNode n = findNode(deptName);
        return n == null || !(n.getUserObject() instanceof Dept) ? null : stateOf(n).name();
    }

    private DefaultMutableTreeNode findNode(String key) {
        Enumeration<TreeNode> e = ((DefaultMutableTreeNode) model.getRoot()).preorderEnumeration();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            Object o = n.getUserObject();
            if ((o instanceof Dept && ((Dept) o).name.equals(key))
                    || (o instanceof Person && ((Person) o).entry.getId().equals(key))) {
                return n;
            }
        }
        return null;
    }

    /** 체크된 사람들의 아이디 (트리 순서, 중복 없음). */
    public List<String> checkedIds() {
        Set<String> ids = new LinkedHashSet<>();
        Enumeration<TreeNode> e = ((DefaultMutableTreeNode) model.getRoot()).preorderEnumeration();
        while (e.hasMoreElements()) {
            Object o = ((DefaultMutableTreeNode) e.nextElement()).getUserObject();
            if (o instanceof Person && ((Person) o).checked) {
                ids.add(((Person) o).entry.getId());
            }
        }
        return new ArrayList<>(ids);
    }

    /** 이 사람들을 체크한 상태로 만든다 (작성 창의 [받는 사람 선택]을 열 때 지금 받는 사람, U57). 다른 사람은 풀린다. */
    public void setChecked(java.util.Collection<String> ids) {
        for (Person p : people((DefaultMutableTreeNode) model.getRoot())) {
            p.checked = ids.contains(p.entry.getId());
        }
        repaint();
    }

    /** 체크를 모두 푼다 ([메시지 쓰기] 뒤, U53). */
    public void clearChecks() {
        for (Person p : people((DefaultMutableTreeNode) model.getRoot())) {
            p.checked = false;
        }
        repaint();
    }

    /** 접속 상태가 바뀐 사람의 ●/○만 바꾼다 (PRESENCE). */
    public void setOnline(String userId, boolean online) {
        Enumeration<TreeNode> e = ((DefaultMutableTreeNode) model.getRoot()).preorderEnumeration();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            if (n.getUserObject() instanceof Person) {
                Person p = (Person) n.getUserObject();
                if (p.entry.getId().equals(userId)) {
                    p.entry = new UserEntry(p.entry.getId(), p.entry.getName(), online);
                    model.nodeChanged(n);
                }
            }
        }
    }

    // =====================================================================
    // 그리기
    // =====================================================================

    private final class Renderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, focus);
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) value;
            Object o = n.getUserObject();
            if (o instanceof Person) {
                setIcon(new BoxIcon(((Person) o).checked ? State.ALL : State.NONE));
            } else if (o instanceof Dept) {
                setIcon(new BoxIcon(stateOf(n)));
                setText(((Dept) o).name + "  (" + people(n).size() + ")");
            }
            setIconTextGap(BOX_GAP);
            return this;
        }
    }

    /** 체크 상자 그림: 없음 ☐ / 일부 ▣ / 모두 ☑. */
    private static final class BoxIcon implements Icon {
        private final State state;

        BoxIcon(State state) {
            this.state = state;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillRect(x, y, BOX - 1, BOX - 1);
            g2.setColor(new Color(0x70, 0x70, 0x70));
            g2.drawRect(x, y, BOX - 1, BOX - 1);
            g2.setColor(new Color(0x1E, 0x6F, 0xD9));
            if (state == State.ALL) {
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(x + 3, y + 6, x + 5, y + 9);
                g2.drawLine(x + 5, y + 9, x + 10, y + 3);
            } else if (state == State.SOME) {
                g2.fillRect(x + 3, y + 3, BOX - 6, BOX - 6);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return BOX;
        }

        @Override
        public int getIconHeight() {
            return BOX;
        }
    }
}
