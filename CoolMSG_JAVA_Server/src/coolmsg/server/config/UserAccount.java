package coolmsg.server.config;

/**
 * 사용자 한 명. users.txt의 한 줄에 해당한다.  설계서 3.2
 *
 * 줄 형식: 아이디|이름|솔트|비밀번호해시|상태(active 또는 deleted)
 *
 * 불변(immutable) 객체다. 이름이나 비밀번호를 바꿀 때는 새 객체를 만든다(withName 등).
 * 그래서 소켓 스레드가 이 객체를 들고 있는 동안 관리 화면이 값을 바꿔도, 들고 있는 객체는 중간에 바뀌지 않는다.
 * 비밀번호는 원문이 아니라 솔트와 해시만 들고 있다.
 */
public final class UserAccount {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DELETED = "deleted";

    private final String id;
    private final String name;
    private final String salt;
    private final String passwordHash;
    private final boolean deleted;

    public UserAccount(String id, String name, String salt, String passwordHash, boolean deleted) {
        this.id = id;
        this.name = name;
        this.salt = salt;
        this.passwordHash = passwordHash;
        this.deleted = deleted;
    }

    public UserAccount withName(String newName) {
        return new UserAccount(id, newName, salt, passwordHash, deleted);
    }

    public UserAccount withPassword(String newSalt, String newHash) {
        return new UserAccount(id, name, newSalt, newHash, deleted);
    }

    public UserAccount withDeleted(boolean newDeleted) {
        return new UserAccount(id, name, salt, passwordHash, newDeleted);
    }

    /** users.txt에 쓸 한 줄. */
    public String toLine() {
        return id + "|" + name + "|" + salt + "|" + passwordHash + "|" + (deleted ? STATUS_DELETED : STATUS_ACTIVE);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSalt() {
        return salt;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
