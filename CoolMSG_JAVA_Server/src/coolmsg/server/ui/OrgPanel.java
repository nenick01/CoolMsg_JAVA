package coolmsg.server.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import coolmsg.common.model.OrgTree;
import coolmsg.server.config.OrgChart;
import coolmsg.server.config.UserAccount;
import coolmsg.server.config.UserDirectory;

/**
 * [조직도 관리] 탭 (설계서 3.5, 5장 U54·U56).
 *
 * - 왼쪽: 부서 트리(JTree). 맨 위 부서는 하나(기관 이름)이고, 없으면 서버 이름으로 만든다. 이름만 바꿀 수 있고 삭제할 수 없다.
 * - 오른쪽: 미배치 사용자(어느 부서에도 없는 활성 사용자, 등록 순서).
 * - [◀] 오른쪽에서 고른 사람을 왼쪽에서 고른 부서에 넣기, [▶] 부서에서 빼서 미배치로.
 * - [▲] [▼] 같은 부서 안에서 순서 바꾸기 — 이 순서가 클라이언트 조직도 순서.
 * - [저장] org.txt와 서버 메모리에 바로 반영(그다음 ORG 요청부터). 저장하지 않고 탭을 떠나면 확인(U56).
 * 편집은 화면의 트리에서만 하고, 저장할 때 OrgTree로 바꿔 OrgChart에 넘긴다.
 */
public class OrgPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 트리 칸에 들어가는 값: 부서 이름 또는 사용자. */
    private static final class Item {
        final boolean dept;
        String name;          // 부서 이름
        final String userId;  // 사용자 아이디
        final String label;   // 사용자 표시 "이름 (아이디)"

        private Item(boolean dept, String name, String userId, String label) {
            this.dept = dept;
            this.name = name;
            this.userId = userId;
            this.label = label;
        }

        static Item dept(String name) {
            return new Item(true, name, null, null);
        }

        static Item user(UserAccount u) {
            return new Item(false, null, u.getId(), u.getName() + " (" + u.getId() + ")");
        }

        @Override
        public String toString() {
            return dept ? "▣ " + name : label;
        }
    }

    private final OrgChart chart;
    private final UserDirectory directory;
    private final StatusPanel status;
    private final String serverName;

    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final JTree tree = new JTree(treeModel);
    private final DefaultListModel<UserAccount> unassignedModel = new DefaultListModel<>();
    private final JList<UserAccount> unassigned = new JList<>(unassignedModel);
    private final JLabel dirtyLabel = new JLabel(" ");
    private boolean dirty;

    public OrgPanel(OrgChart chart, UserDirectory directory, StatusPanel status, String serverName) {
        super(new BorderLayout(8, 8));
        this.chart = chart;
        this.directory = directory;
        this.status = status;
        this.serverName = serverName;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        unassigned.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel,
                    boolean focus) {
                UserAccount u = (UserAccount) value;
                return super.getListCellRendererComponent(list, u.getName() + " (" + u.getId() + ")", index, sel, focus);
            }
        });

        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("조직도"));
        JScrollPane listScroll = new JScrollPane(unassigned);
        listScroll.setBorder(BorderFactory.createTitledBorder("미배치 사용자"));
        listScroll.setPreferredSize(new Dimension(220, 300));

        JButton assign = new JButton("◀");
        JButton unassign = new JButton("▶");
        assign.setToolTipText("오른쪽에서 고른 사용자를 왼쪽에서 고른 부서에 넣기");
        unassign.setToolTipText("왼쪽에서 고른 사용자를 부서에서 빼기 (미배치로)");
        assign.addActionListener(e -> assignUsers());
        unassign.addActionListener(e -> unassignUser());
        JPanel middle = new JPanel(new GridBagLayout());
        JPanel arrows = new JPanel();
        arrows.setLayout(new BoxLayout(arrows, BoxLayout.Y_AXIS));
        arrows.add(assign);
        arrows.add(Box.createVerticalStrut(8));
        arrows.add(unassign);
        middle.add(arrows);

        JPanel center = new JPanel(new BorderLayout(4, 0));
        center.add(treeScroll, BorderLayout.CENTER);
        JPanel right = new JPanel(new BorderLayout(4, 0));
        right.add(middle, BorderLayout.WEST);
        right.add(listScroll, BorderLayout.CENTER);
        center.add(right, BorderLayout.EAST);

        JButton addDept = new JButton("부서 추가");
        JButton rename = new JButton("이름 변경");
        JButton deleteDept = new JButton("부서 삭제");
        JButton up = new JButton("▲ 위로");
        JButton down = new JButton("▼ 아래로");
        JButton save = new JButton("저장");
        addDept.addActionListener(e -> addDept());
        rename.addActionListener(e -> renameDept());
        deleteDept.addActionListener(e -> deleteDept());
        up.addActionListener(e -> move(-1));
        down.addActionListener(e -> move(1));
        save.addActionListener(e -> save());
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton b : new JButton[] {addDept, rename, deleteDept, up, down}) {
            left.add(b);
        }
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        rightButtons.add(dirtyLabel);
        rightButtons.add(save);
        bottom.add(left, BorderLayout.WEST);
        bottom.add(rightButtons, BorderLayout.EAST);

        add(center, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        reload();
    }

    // =====================================================================
    // 탭에 들어오고 나갈 때 (U56)
    // =====================================================================

    /** 탭에 들어올 때: 저장 안 한 편집이 없으면 서버 메모리와 최신 사용자 목록으로 다시 그린다. */
    public void onEnter() {
        if (!dirty) {
            reload();
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    /**
     * 탭을 떠나도 되는가. 저장 안 한 편집이 있으면 [저장] [버리기] [취소]를 묻는다.
     *
     * @return 떠나도 되면 true (저장했거나 버림), 취소하면 false
     */
    public boolean confirmLeave() {
        if (!dirty) {
            return true;
        }
        Object[] options = {"저장", "버리기", "취소"};
        int answer = JOptionPane.showOptionDialog(this, "조직도에 저장하지 않은 변경이 있습니다.", "조직도 관리",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (answer == 0) {
            return save();
        }
        if (answer == 1) {
            reload(); // 버리기: 저장된 상태로
            return true;
        }
        return false;
    }

    // =====================================================================

    private void reload() {
        OrgTree snapshot = chart.snapshot();
        OrgTree.Node root = snapshot.getRoot();
        if (root == null) {
            root = OrgTree.Node.dept(serverName); // 맨 위 부서가 없으면 서버 이름으로 (U54)
        }
        Set<String> placed = new HashSet<>();
        DefaultMutableTreeNode rootNode = toTreeNode(root, placed);
        treeModel.setRoot(rootNode);
        unassignedModel.clear();
        for (UserAccount u : directory.getUsers()) {
            if (!u.isDeleted() && !placed.contains(u.getId())) {
                unassignedModel.addElement(u);
            }
        }
        expandAll();
        setDirty(false);
    }

    private DefaultMutableTreeNode toTreeNode(OrgTree.Node node, Set<String> placed) {
        DefaultMutableTreeNode t = new DefaultMutableTreeNode(Item.dept(node.getName()));
        for (OrgTree.Node c : node.getChildren()) {
            if (c.isDept()) {
                t.add(toTreeNode(c, placed));
            } else {
                UserAccount u = directory.find(c.getName());
                if (u != null && !u.isDeleted()) {
                    t.add(new DefaultMutableTreeNode(Item.user(u), false));
                    placed.add(u.getId());
                }
            }
        }
        return t;
    }

    private OrgTree.Node toOrgNode(DefaultMutableTreeNode t) {
        Item item = (Item) t.getUserObject();
        OrgTree.Node n = OrgTree.Node.dept(item.name);
        for (int i = 0; i < t.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) t.getChildAt(i);
            Item ci = (Item) c.getUserObject();
            n.getChildren().add(ci.dept ? toOrgNode(c) : OrgTree.Node.user(ci.userId));
        }
        return n;
    }

    private boolean save() {
        OrgTree t = new OrgTree(toOrgNode((DefaultMutableTreeNode) treeModel.getRoot()));
        try {
            chart.save(t);
            status.log("조직도 저장 (다음 ORG 요청부터 반영)");
            setDirty(false);
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "조직도를 저장하지 못했습니다.\n원인: " + e.getMessage(), "저장 실패",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void setDirty(boolean d) {
        dirty = d;
        dirtyLabel.setText(d ? "저장하지 않은 변경이 있습니다" : " ");
    }

    // =====================================================================
    // 편집
    // =====================================================================

    private DefaultMutableTreeNode selectedNode() {
        TreePath p = tree.getSelectionPath();
        return p == null ? null : (DefaultMutableTreeNode) p.getLastPathComponent();
    }

    /** 고른 칸이 부서면 그 부서, 사용자면 그 사용자의 부서, 아무것도 안 골랐으면 맨 위 부서. */
    private DefaultMutableTreeNode selectedDept() {
        DefaultMutableTreeNode n = selectedNode();
        if (n == null) {
            return (DefaultMutableTreeNode) treeModel.getRoot();
        }
        return ((Item) n.getUserObject()).dept ? n : (DefaultMutableTreeNode) n.getParent();
    }

    /** 이름 규칙(U54) + 같은 부서 아래 같은 이름 금지. 맞으면 null. */
    private static String checkName(DefaultMutableTreeNode parent, String name, DefaultMutableTreeNode except) {
        String problem = OrgTree.checkDeptName(name);
        if (problem != null) {
            return problem;
        }
        if (parent != null) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                DefaultMutableTreeNode c = (DefaultMutableTreeNode) parent.getChildAt(i);
                Item ci = (Item) c.getUserObject();
                if (c != except && ci.dept && ci.name.equals(name.strip())) {
                    return "같은 부서 아래에 '" + name.strip() + "' 부서가 이미 있습니다.";
                }
            }
        }
        return null;
    }

    private String askName(String title, String initial, DefaultMutableTreeNode parent, DefaultMutableTreeNode except) {
        String name = initial;
        while (true) {
            name = (String) JOptionPane.showInputDialog(this, "부서 이름 (1~20자, |·> 제외)", title,
                    JOptionPane.PLAIN_MESSAGE, null, null, name);
            if (name == null) {
                return null;
            }
            String problem = checkName(parent, name, except);
            if (problem == null) {
                return name.strip();
            }
            JOptionPane.showMessageDialog(this, problem, title, JOptionPane.WARNING_MESSAGE);
        }
    }

    private void addDept() {
        DefaultMutableTreeNode parent = selectedDept();
        String name = askName("부서 추가", "", parent, null);
        if (name == null) {
            return;
        }
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(Item.dept(name));
        treeModel.insertNodeInto(n, parent, parent.getChildCount());
        tree.setSelectionPath(new TreePath(n.getPath()));
        setDirty(true);
    }

    private void renameDept() {
        DefaultMutableTreeNode n = selectedNode();
        if (n == null || !((Item) n.getUserObject()).dept) {
            JOptionPane.showMessageDialog(this, "이름을 바꿀 부서를 고르세요.");
            return;
        }
        Item item = (Item) n.getUserObject();
        String name = askName("이름 변경", item.name, (DefaultMutableTreeNode) n.getParent(), n);
        if (name != null && !name.equals(item.name)) {
            item.name = name;
            treeModel.nodeChanged(n);
            setDirty(true);
        }
    }

    private void deleteDept() {
        DefaultMutableTreeNode n = selectedNode();
        if (n == null || !((Item) n.getUserObject()).dept) {
            JOptionPane.showMessageDialog(this, "삭제할 부서를 고르세요.");
            return;
        }
        if (n.isRoot()) {
            JOptionPane.showMessageDialog(this, "맨 위 부서(기관 이름)는 삭제할 수 없습니다. 이름만 바꿀 수 있습니다.");
            return;
        }
        List<DefaultMutableTreeNode> users = new ArrayList<>();
        for (int i = 0; i < n.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) n.getChildAt(i);
            if (((Item) c.getUserObject()).dept) {
                JOptionPane.showMessageDialog(this, "하위 부서가 있는 부서는 삭제할 수 없습니다. 하위 부서를 먼저 지우세요.");
                return;
            }
            users.add(c);
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "'" + ((Item) n.getUserObject()).name + "' 부서를 삭제할까요?\n이 부서의 사용자 " + users.size()
                        + "명은 미배치로 갑니다.",
                "부서 삭제", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        for (DefaultMutableTreeNode u : users) {
            backToUnassigned(((Item) u.getUserObject()).userId);
        }
        treeModel.removeNodeFromParent(n);
        setDirty(true);
    }

    private void move(int delta) {
        DefaultMutableTreeNode n = selectedNode();
        if (n == null || n.isRoot()) {
            return;
        }
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) n.getParent();
        int index = parent.getIndex(n) + delta;
        if (index < 0 || index >= parent.getChildCount()) {
            return;
        }
        treeModel.removeNodeFromParent(n);
        treeModel.insertNodeInto(n, parent, index);
        tree.setSelectionPath(new TreePath(n.getPath()));
        setDirty(true);
    }

    private void assignUsers() {
        List<UserAccount> chosen = unassigned.getSelectedValuesList();
        if (chosen.isEmpty()) {
            JOptionPane.showMessageDialog(this, "오른쪽 미배치 사용자 목록에서 넣을 사람을 고르세요.");
            return;
        }
        DefaultMutableTreeNode dept = selectedDept();
        for (UserAccount u : chosen) {
            treeModel.insertNodeInto(new DefaultMutableTreeNode(Item.user(u), false), dept, dept.getChildCount());
            unassignedModel.removeElement(u);
        }
        tree.expandPath(new TreePath(dept.getPath()));
        setDirty(true);
    }

    private void unassignUser() {
        DefaultMutableTreeNode n = selectedNode();
        if (n == null || ((Item) n.getUserObject()).dept) {
            JOptionPane.showMessageDialog(this, "왼쪽 조직도에서 뺄 사용자를 고르세요.");
            return;
        }
        backToUnassigned(((Item) n.getUserObject()).userId);
        treeModel.removeNodeFromParent(n);
        setDirty(true);
    }

    /** 미배치 목록에 등록 순서를 지켜 넣는다. */
    private void backToUnassigned(String userId) {
        List<UserAccount> all = directory.getUsers();
        List<String> order = new ArrayList<>();
        for (UserAccount u : all) {
            order.add(u.getId());
        }
        UserAccount account = directory.find(userId);
        if (account == null) {
            return;
        }
        int pos = 0;
        while (pos < unassignedModel.size() && order.indexOf(unassignedModel.get(pos).getId()) < order.indexOf(userId)) {
            pos++;
        }
        unassignedModel.add(pos, account);
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }
}
