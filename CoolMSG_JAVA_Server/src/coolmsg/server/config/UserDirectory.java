package coolmsg.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import coolmsg.common.file.AtomicFile;
import coolmsg.server.security.PasswordHasher;

/**
 * 메모리의 사용자 목록 원본 (설계서 3.1, 3.5, 결정 10).
 *
 * - 관리 화면(Swing 스레드)과 소켓 스레드가 이 객체 하나를 함께 쓴다. 그래서 목록을 읽고 바꾸는 코드는
 *   모두 synchronized 안에서만 실행한다(동시성 규칙 C8). 밖으로 줄 때는 복사본을 준다.
 * - 바뀔 때마다 users.txt에 바로 저장한다. 저장에 실패하면 메모리도 되돌린다(U20).
 * - users.txt는 서버를 켤 때 한 번만 읽는다(U23).
 *
 * 비밀번호 해시 계산(PBKDF2, 수십 ms)은 일부러 느린 작업이라 잠금 밖에서 한다.
 * 잠금을 쥔 채 오래 걸리는 일을 하면 다른 스레드(로그인 등)가 그동안 기다리게 된다.
 */
public class UserDirectory {

    public static final int ID_MIN_LENGTH = 3;
    public static final int ID_MAX_LENGTH = 20;
    public static final int NAME_MAX_LENGTH = 20;
    public static final int PASSWORD_MIN_LENGTH = 4;

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9]+");

    /** 없는 아이디로 로그인할 때도 해시 계산을 한 번 해서, 응답 시간으로 아이디 존재 여부를 알아내지 못하게 한다. */
    private static final String DUMMY_SALT = PasswordHasher.newSalt();
    private static final String DUMMY_HASH = PasswordHasher.hash(DUMMY_SALT, "dummy".toCharArray());

    private final Path file;
    private final List<UserAccount> users = new ArrayList<>(); // 등록 순서 유지 (REQ-F-021)
    private final List<String> headerComments = new ArrayList<>(); // 맨 위 주석 블록 (U20)
    private final List<String> warnings = new ArrayList<>();
    private boolean hadSkippedLines; // 읽을 때 형식 오류로 건너뛴 줄이 있었나
    private boolean backedUp;        // 그 경우 users.txt.bak 백업을 이미 했나

    public UserDirectory(Path file) {
        this.file = file;
    }

    // =====================================================================
    // 읽기
    // =====================================================================

    /**
     * users.txt를 읽는다.
     * - 파일이 없으면 빈 목록으로 시작한다(경고).
     * - 파일이 있는데 읽지 못하면 IOException을 그대로 던진다. 빈 목록으로 시작해 덮어쓰는 일을 막기 위해
     *   부르는 쪽(ServerMain)은 서버 시작을 중단한다(U22).
     * - 형식이 잘못된 줄은 건너뛰고 경고를 남긴다.
     */
    public synchronized void load() throws IOException {
        users.clear();
        headerComments.clear();
        warnings.clear();
        hadSkippedLines = false;
        backedUp = false;

        if (!Files.exists(file)) {
            warnings.add("사용자 목록 파일이 없어 빈 목록으로 시작합니다: " + file);
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8); // UTF-8이 아니면 예외 (U22)

        boolean inHeader = true;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip(); // 줄 끝 \r 허용 (U11)
            if (inHeader && (line.isEmpty() || line.startsWith("#"))) {
                headerComments.add(line);
                continue;
            }
            inHeader = false;
            if (line.isEmpty() || line.startsWith("#")) {
                continue; // 맨 위가 아닌 주석·빈 줄은 보존하지 않는다
            }
            UserAccount account = parseLine(line, i + 1);
            if (account == null) {
                hadSkippedLines = true;
            } else if (indexOf(account.getId()) >= 0) {
                warnings.add((i + 1) + "번째 줄: 아이디 " + account.getId() + "가 이미 있어 건너뜁니다.");
                hadSkippedLines = true;
            } else {
                users.add(account);
            }
        }
        // 주석 블록 끝의 빈 줄은 다시 쓸 때 필요 없다
        while (!headerComments.isEmpty() && headerComments.get(headerComments.size() - 1).isEmpty()) {
            headerComments.remove(headerComments.size() - 1);
        }
    }

    /** 한 줄을 읽는다. 형식이 틀리면 경고를 남기고 null. 5번째 칸이 없으면 active로 본다. */
    private UserAccount parseLine(String line, int lineNo) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 4 && parts.length != 5) {
            warnings.add(lineNo + "번째 줄 형식 오류 (칸 " + parts.length + "개, 4~5개여야 함). 건너뜁니다.");
            return null;
        }
        String id = parts[0];
        if (!isValidId(id)) {
            warnings.add(lineNo + "번째 줄: 아이디 형식이 아닙니다(" + id + "). 건너뜁니다.");
            return null;
        }
        if (parts[1].isEmpty() || parts[2].isEmpty() || parts[3].isEmpty()) {
            warnings.add(lineNo + "번째 줄: 빈 칸이 있습니다. 건너뜁니다.");
            return null;
        }
        boolean deleted = false;
        if (parts.length == 5) {
            if (UserAccount.STATUS_DELETED.equals(parts[4])) {
                deleted = true;
            } else if (!UserAccount.STATUS_ACTIVE.equals(parts[4])) {
                warnings.add(lineNo + "번째 줄: 상태가 active/deleted가 아닙니다(" + parts[4] + "). 건너뜁니다.");
                return null;
            }
        }
        return new UserAccount(id, parts[1], parts[2], parts[3], deleted);
    }

    /** 사용자 전체 (등록 순서, 삭제된 사용자 포함, 읽기 전용 복사본). */
    public synchronized List<UserAccount> getUsers() {
        return List.copyOf(users);
    }

    /** 아이디로 찾기. 없으면 null. */
    public synchronized UserAccount find(String id) {
        int i = indexOf(id);
        return i < 0 ? null : users.get(i);
    }

    /** 읽기·저장하면서 생긴 경고 (읽기 전용 복사본). */
    public synchronized List<String> getWarnings() {
        return List.copyOf(warnings);
    }

    /**
     * 로그인 확인. 아이디가 없거나, 삭제됐거나, 비밀번호가 틀리면 모두 false (어느 쪽인지 알려 주지 않는다).
     * 해시 계산은 잠금 밖에서 한다.
     */
    public boolean authenticate(String id, char[] password) {
        UserAccount account = find(id); // find는 synchronized, 계정 객체는 불변이라 밖에서 써도 안전
        if (account == null || account.isDeleted()) {
            PasswordHasher.matches(DUMMY_SALT, DUMMY_HASH, password); // 응답 시간을 맞추기 위한 계산
            return false;
        }
        return PasswordHasher.matches(account.getSalt(), account.getPasswordHash(), password);
    }

    // =====================================================================
    // 바꾸기 (관리 화면)
    // =====================================================================

    /** 사용자 추가 (REQ-F-204, 205). 맨 뒤(등록 순서 마지막)에 붙는다. */
    public UserAccount add(String id, String name, char[] password) throws UserDirectoryException, IOException {
        checkId(id);
        String cleanName = checkName(name);
        checkPassword(password);
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(salt, password); // 잠금 밖에서 느린 계산
        synchronized (this) {
            if (indexOf(id) >= 0) {
                UserAccount existing = users.get(indexOf(id));
                throw new UserDirectoryException(existing.isDeleted()
                        ? "삭제된 사용자의 아이디라 다시 쓸 수 없습니다: " + id + " (필요하면 [복구]를 사용하세요)"
                        : "이미 있는 아이디입니다: " + id);
            }
            UserAccount account = new UserAccount(id, cleanName, salt, hash, false);
            List<UserAccount> before = new ArrayList<>(users);
            users.add(account);
            saveOrRollback(before);
            return account;
        }
    }

    /** 이름 수정. 아이디는 바꿀 수 없다. */
    public void rename(String id, String newName) throws UserDirectoryException, IOException {
        String cleanName = checkName(newName);
        synchronized (this) {
            int i = activeIndexOf(id);
            List<UserAccount> before = new ArrayList<>(users);
            users.set(i, users.get(i).withName(cleanName));
            saveOrRollback(before);
        }
    }

    /** 비밀번호 초기화. 접속 중인 연결은 유지한다(U7). */
    public void resetPassword(String id, char[] newPassword) throws UserDirectoryException, IOException {
        checkPassword(newPassword);
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(salt, newPassword);
        synchronized (this) {
            int i = activeIndexOf(id);
            List<UserAccount> before = new ArrayList<>(users);
            users.set(i, users.get(i).withPassword(salt, hash));
            saveOrRollback(before);
        }
    }

    /** 삭제: 줄을 지우지 않고 deleted로 표시한다(결정 14). 대기 파일은 남긴다(U19). */
    public void delete(String id) throws UserDirectoryException, IOException {
        synchronized (this) {
            int i = activeIndexOf(id);
            List<UserAccount> before = new ArrayList<>(users);
            users.set(i, users.get(i).withDeleted(true));
            saveOrRollback(before);
        }
    }

    /** 복구: 새 비밀번호로 다시 활성화한다. 등록 순서 자리는 그대로(U18). */
    public void restore(String id, char[] newPassword) throws UserDirectoryException, IOException {
        checkPassword(newPassword);
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(salt, newPassword);
        synchronized (this) {
            int i = indexOf(id);
            if (i < 0) {
                throw new UserDirectoryException("없는 아이디입니다: " + id);
            }
            if (!users.get(i).isDeleted()) {
                throw new UserDirectoryException("삭제된 사용자가 아닙니다: " + id);
            }
            List<UserAccount> before = new ArrayList<>(users);
            users.set(i, users.get(i).withPassword(salt, hash).withDeleted(false));
            saveOrRollback(before);
        }
    }

    // =====================================================================
    // 입력 검사 (U17) — 관리 화면이 미리 검사할 때도 쓴다
    // =====================================================================

    public static boolean isValidId(String id) {
        return id != null && id.length() >= ID_MIN_LENGTH && id.length() <= ID_MAX_LENGTH
                && ID_PATTERN.matcher(id).matches();
    }

    public static void checkId(String id) throws UserDirectoryException {
        if (!isValidId(id)) {
            throw new UserDirectoryException("아이디는 영문 소문자와 숫자로 " + ID_MIN_LENGTH + "~" + ID_MAX_LENGTH
                    + "자여야 합니다.");
        }
    }

    /** 이름 검사. 앞뒤 공백을 뺀 이름을 돌려준다. */
    public static String checkName(String name) throws UserDirectoryException {
        String clean = (name == null) ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > NAME_MAX_LENGTH) {
            throw new UserDirectoryException("이름은 1~" + NAME_MAX_LENGTH + "자여야 합니다.");
        }
        if (clean.indexOf('|') >= 0 || clean.indexOf('\n') >= 0 || clean.indexOf('\r') >= 0) {
            throw new UserDirectoryException("이름에 '|'나 줄바꿈을 쓸 수 없습니다.");
        }
        return clean;
    }

    public static void checkPassword(char[] password) throws UserDirectoryException {
        if (password == null || password.length < PASSWORD_MIN_LENGTH) {
            throw new UserDirectoryException("비밀번호는 " + PASSWORD_MIN_LENGTH + "자 이상이어야 합니다.");
        }
    }

    /** 두 비밀번호 입력이 같은지 (관리 화면용). */
    public static void checkSame(char[] a, char[] b) throws UserDirectoryException {
        if (!Arrays.equals(a, b)) {
            throw new UserDirectoryException("두 비밀번호가 다릅니다.");
        }
    }

    // =====================================================================
    // 내부
    // =====================================================================

    private int indexOf(String id) {
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** 활성 사용자의 위치. 없거나 삭제된 사용자면 예외. */
    private int activeIndexOf(String id) throws UserDirectoryException {
        int i = indexOf(id);
        if (i < 0) {
            throw new UserDirectoryException("없는 아이디입니다: " + id);
        }
        if (users.get(i).isDeleted()) {
            throw new UserDirectoryException("삭제된 사용자는 바꿀 수 없습니다: " + id + " (먼저 [복구]하세요)");
        }
        return i;
    }

    /** 저장한다. 실패하면 메모리 목록을 before로 되돌리고 예외를 다시 던진다(U20). 잠금 안에서 부른다. */
    private void saveOrRollback(List<UserAccount> before) throws IOException {
        try {
            save();
        } catch (IOException e) {
            users.clear();
            users.addAll(before);
            throw e;
        }
    }

    private void save() throws IOException {
        if (hadSkippedLines && !backedUp && Files.exists(file)) {
            // 읽을 때 건너뛴 줄이 덮어쓰기로 사라지기 전에 원래 파일을 보관한다 (U20)
            Path backup = file.resolveSibling(file.getFileName() + ".bak");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            warnings.add("형식 오류로 읽지 못한 줄이 있어 원래 파일을 백업했습니다: " + backup);
            backedUp = true;
        }
        StringBuilder sb = new StringBuilder();
        for (String comment : headerComments) {
            sb.append(comment).append('\n');
        }
        for (UserAccount account : users) {
            sb.append(account.toLine()).append('\n');
        }
        AtomicFile.writeText(file, sb.toString());
    }
}
