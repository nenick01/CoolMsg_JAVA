package coolmsg.common.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 조직도 트리와 org.txt 형식 (설계서 2.10, 3.2, 5장 U54).
 *
 *   D|한빛초등학교              부서 (하위 부서는 > 로 구분)
 *   U|한빛초등학교>교무실|kim    그 부서의 사용자
 *
 * - 적힌 순서가 곧 화면 순서. 한 부서 안에서 하위 부서와 사용자가 적힌 순서대로 섞여 있을 수 있다.
 * - 맨 위 부서는 하나(기관 이름). 두 번째 맨 위 부서와 그 아래는 건너뛴다.
 * - 사람이 고칠 수 있는 설정 형식이라 관대하게 읽는다(U11): 잘못된 줄은 건너뛰고 warnings에 적는다.
 * 서버(파일 저장·ORG_OK)와 클라이언트(조직도 화면)가 같은 규칙을 쓰도록 Common에 둔다.
 */
public final class OrgTree {

    public static final int DEPT_NAME_MAX_LENGTH = 20;

    /** 트리의 칸 하나: 부서(하위 칸이 있음) 또는 사용자(아이디). */
    public static final class Node {
        private final boolean dept;
        private String name; // 부서 이름 또는 사용자 아이디
        private final List<Node> children = new ArrayList<>();

        private Node(boolean dept, String name) {
            this.dept = dept;
            this.name = name;
        }

        public static Node dept(String name) {
            return new Node(true, name);
        }

        public static Node user(String userId) {
            return new Node(false, userId);
        }

        public boolean isDept() {
            return dept;
        }

        /** 부서 이름 또는 사용자 아이디. */
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** 하위 칸 (부서만). 고칠 수 있는 목록이다. */
        public List<Node> getChildren() {
            return children;
        }

        Node copy() {
            Node n = new Node(dept, name);
            for (Node c : children) {
                n.children.add(c.copy());
            }
            return n;
        }

        Node findChildDept(String childName) {
            for (Node c : children) {
                if (c.dept && c.name.equals(childName)) {
                    return c;
                }
            }
            return null;
        }
    }

    private Node root; // 맨 위 부서. 없으면 null

    public OrgTree() {
    }

    public OrgTree(Node root) {
        this.root = root;
    }

    public Node getRoot() {
        return root;
    }

    public void setRoot(Node root) {
        this.root = root;
    }

    public OrgTree copy() {
        return new OrgTree(root == null ? null : root.copy());
    }

    // =====================================================================
    // 읽기 (관대하게)
    // =====================================================================

    /** org.txt 형식의 줄들을 읽는다. #·빈 줄은 무시. 잘못된 줄은 건너뛰고 warnings에. */
    public static OrgTree parse(List<String> lines, List<String> warnings) {
        OrgTree t = new OrgTree();
        List<String> placed = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String where = (i + 1) + "번째 줄: ";
            String[] p = line.split("\\|", -1);
            if (p[0].equals("D") && p.length == 2) {
                t.parseDept(p[1], where, warnings);
            } else if (p[0].equals("U") && p.length == 3) {
                Node dept = t.findDept(splitPath(p[1]));
                String id = p[2].strip();
                if (dept == null) {
                    warnings.add(where + "앞에서 정의되지 않은 부서라 건너뜁니다: " + p[1]);
                } else if (id.isEmpty()) {
                    warnings.add(where + "아이디가 비어 있어 건너뜁니다.");
                } else if (placed.contains(id)) {
                    warnings.add(where + id + "는 이미 다른 부서에 있어 건너뜁니다 (처음 나온 부서만 씀).");
                } else {
                    dept.children.add(Node.user(id));
                    placed.add(id);
                }
            } else {
                warnings.add(where + "형식이 잘못되어 건너뜁니다: " + line);
            }
        }
        return t;
    }

    private void parseDept(String pathText, String where, List<String> warnings) {
        List<String> path = splitPath(pathText);
        for (String name : path) {
            String problem = checkDeptName(name);
            if (problem != null) {
                warnings.add(where + problem + " 건너뜁니다: " + pathText);
                return;
            }
        }
        if (path.size() == 1) {
            if (root == null) {
                root = Node.dept(path.get(0));
            } else if (!root.name.equals(path.get(0))) {
                warnings.add(where + "맨 위 부서는 하나만 둘 수 있어 건너뜁니다: " + pathText);
            } else {
                warnings.add(where + "이미 있는 부서라 건너뜁니다: " + pathText);
            }
            return;
        }
        Node parent = findDept(path.subList(0, path.size() - 1));
        String last = path.get(path.size() - 1);
        if (parent == null) {
            warnings.add(where + "상위 부서가 앞에서 정의되지 않아 건너뜁니다: " + pathText);
        } else if (parent.findChildDept(last) != null) {
            warnings.add(where + "이미 있는 부서라 건너뜁니다: " + pathText);
        } else {
            parent.children.add(Node.dept(last));
        }
    }

    private static List<String> splitPath(String text) {
        List<String> path = new ArrayList<>();
        for (String s : text.split(">", -1)) {
            path.add(s.strip());
        }
        return path;
    }

    private Node findDept(List<String> path) {
        if (root == null || path.isEmpty() || !root.name.equals(path.get(0))) {
            return null;
        }
        Node cur = root;
        for (int i = 1; i < path.size() && cur != null; i++) {
            cur = cur.findChildDept(path.get(i));
        }
        return cur;
    }

    /** 부서 이름 규칙 (U54). 맞으면 null, 아니면 이유. 이름은 앞뒤 공백을 뺀 값으로 검사한다. */
    public static String checkDeptName(String name) {
        if (name == null || name.isBlank()) {
            return "부서 이름이 비어 있습니다.";
        }
        if (name.strip().length() > DEPT_NAME_MAX_LENGTH) {
            return "부서 이름은 " + DEPT_NAME_MAX_LENGTH + "자까지입니다.";
        }
        if (name.contains("|") || name.contains(">") || name.contains("\n") || name.contains("\r")) {
            return "부서 이름에 |, >, 줄바꿈은 쓸 수 없습니다.";
        }
        return null;
    }

    // =====================================================================
    // 쓰기
    // =====================================================================

    /** org.txt 형식 (주석 없이). 적힌 순서 = 트리 순서. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        if (root != null) {
            write(sb, root, root.name);
        }
        return sb.toString();
    }

    private static void write(StringBuilder sb, Node dept, String path) {
        sb.append("D|").append(path).append('\n');
        for (Node c : dept.children) {
            if (c.dept) {
                write(sb, c, path + ">" + c.name);
            } else {
                sb.append("U|").append(path).append('|').append(c.name).append('\n');
            }
        }
    }

    // =====================================================================
    // 사용자 찾기·빼기
    // =====================================================================

    /** keep을 만족하지 않는 사용자를 뺀다 (삭제된·없는 아이디, U55). 뺀 아이디 목록을 돌려준다. */
    public List<String> retainUsers(Predicate<String> keep) {
        List<String> removed = new ArrayList<>();
        if (root != null) {
            retain(root, keep, removed);
        }
        return removed;
    }

    private static void retain(Node dept, Predicate<String> keep, List<String> removed) {
        dept.children.removeIf(c -> {
            if (!c.dept && !keep.test(c.name)) {
                removed.add(c.name);
                return true;
            }
            return false;
        });
        for (Node c : dept.children) {
            if (c.dept) {
                retain(c, keep, removed);
            }
        }
    }

    /** 그 사용자가 속한 부서의 이름. 미배치면 null. */
    public String deptNameOf(String userId) {
        Node d = root == null ? null : findDeptOf(root, userId);
        return d == null ? null : d.name;
    }

    private static Node findDeptOf(Node dept, String userId) {
        for (Node c : dept.children) {
            if (!c.dept && c.name.equals(userId)) {
                return dept;
            }
            if (c.dept) {
                Node found = findDeptOf(c, userId);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 트리에 있는 사용자 아이디 (트리 순서). */
    public List<String> userIds() {
        List<String> ids = new ArrayList<>();
        if (root != null) {
            collect(root, ids);
        }
        return ids;
    }

    private static void collect(Node dept, List<String> ids) {
        for (Node c : dept.children) {
            if (c.dept) {
                collect(c, ids);
            } else {
                ids.add(c.name);
            }
        }
    }
}
