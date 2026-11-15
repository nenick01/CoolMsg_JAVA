package coolmsg.client.model;

/**
 * 사용자 목록의 한 사람 (LOGIN_OK의 "아이디|이름|online" 한 줄).
 */
public final class UserEntry {

    private final String id;
    private final String name;
    private final boolean online;

    public UserEntry(String id, String name, boolean online) {
        this.id = id;
        this.name = name;
        this.online = online;
    }

    /** 한 줄 "아이디|이름|true" 를 읽는다. 형식이 틀리면 null. */
    public static UserEntry parse(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 3 || parts[0].isEmpty()) {
            return null;
        }
        return new UserEntry(parts[0], parts[1], Boolean.parseBoolean(parts[2]));
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isOnline() {
        return online;
    }

    /** 화면 표시용: "● 김철수 (kim)" / "○ 이영희 (lee)". */
    @Override
    public String toString() {
        return (online ? "● " : "○ ") + name + " (" + id + ")";
    }
}
