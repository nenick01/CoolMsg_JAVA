package coolmsg.server.config;

/**
 * 사용자 계정 한 명. users.txt의 한 줄에 해당한다.
 * 비밀번호는 원문이 아니라 솔트와 해시만 들고 있다.
 */
public class UserAccount {

    private final String id;
    private final String name;
    private final String salt;
    private final String passwordHash;

    public UserAccount(String id, String name, String salt, String passwordHash) {
        this.id = id;
        this.name = name;
        this.salt = salt;
        this.passwordHash = passwordHash;
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
}
