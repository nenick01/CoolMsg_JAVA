package coolmsg.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import coolmsg.server.config.AdminConfig;
import coolmsg.server.config.ServerConfig;
import coolmsg.server.config.UserAccount;
import coolmsg.server.config.UserDirectory;
import coolmsg.server.config.UserDirectoryException;

/**
 * 서버 테스트 (구현 1단계: 설정, 사용자 목록). 외부 라이브러리 없이 main으로 실행한다.
 * 결과는 docs/04_테스트기록.md에 기록한다.
 *
 * 모든 테스트는 임시 폴더에서만 파일을 만든다. 실제 config/ 파일은 읽기만 한다(예시 server.txt 확인).
 * 실행: VS Code "Test: Server" (작업 폴더 = CoolMSG_JAVA_Server)
 */
public class ServerTest {

    private static int passed = 0;
    private static int failed = 0;

    private interface Action {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        serverConfig();
        userDirectoryLoad();
        userDirectoryChange();
        userDirectoryFile();
        userDirectoryConcurrency();
        serverContext();

        System.out.println();
        System.out.println("결과: 통과 " + passed + " / 실패 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ===================================================================
    // ServerConfig (설계서 3.2, U11, U21)
    // ===================================================================
    private static void serverConfig() throws Exception {
        section("ServerConfig - server.txt 읽기와 값 검사");
        Path dir = Files.createTempDirectory("coolmsg-config");

        ServerConfig missing = ServerConfig.load(dir.resolve("none.txt"));
        check("파일 없음 → 모두 기본값 + 경고 1개",
                missing.getPort() == 9000 && missing.getServerName().equals("CoolMsg 서버")
                        && missing.getBodyRetentionDays() == 90 && missing.getMaxAttachmentMB() == 10
                        && missing.getWarnings().size() == 1);

        Path good = write(dir, "good.txt", "# 주석\r\n\r\n port = 9100 \r\nserverName=우리 학교\r\nbodyRetentionDays=60\r\n");
        ServerConfig g = ServerConfig.load(good);
        check("\\r\\n·앞뒤 공백·주석·빈 줄을 관대하게 읽음 (U11)",
                g.getPort() == 9100 && g.getServerName().equals("우리 학교") && g.getBodyRetentionDays() == 60
                        && g.getWarnings().isEmpty());
        check("적지 않은 항목은 기본값", g.getAttachmentRetentionDays() == 30 && g.getMaxAttachmentMB() == 10);

        ServerConfig bad = ServerConfig.load(write(dir, "bad.txt",
                "port=80\nbodyRetentionDays=0\nattachmentRetentionDays=abc\nmaxAttachmentMB=50\nserverName=\n"));
        check("범위 밖·숫자 아님·빈 이름 → 그 항목만 기본값",
                bad.getPort() == 9000 && bad.getBodyRetentionDays() == 90 && bad.getAttachmentRetentionDays() == 30
                        && bad.getMaxAttachmentMB() == 10 && bad.getServerName().equals("CoolMsg 서버"));
        check("잘못된 값마다 경고 (5개)", bad.getWarnings().size() == 5);

        ServerConfig edge = ServerConfig.load(write(dir, "edge.txt", "port=1024\nmaxAttachmentMB=1\nport=65535\n"));
        check("경계값 허용, 같은 키 두 번이면 나중 값 + 경고",
                edge.getPort() == 65535 && edge.getMaxAttachmentMB() == 1 && edge.getWarnings().size() == 1);

        ServerConfig unknown = ServerConfig.load(write(dir, "unknown.txt", "colour=blue\n그냥 문장\n"));
        check("모르는 키·'=' 없는 줄 → 무시 + 경고 2개", unknown.getWarnings().size() == 2 && unknown.getPort() == 9000);

        ServerConfig sample = ServerConfig.load(Paths.get("config", "server.txt"));
        check("저장소의 예시 config/server.txt는 경고 없이 읽힘", sample.getWarnings().isEmpty());
    }

    // ===================================================================
    // UserDirectory 읽기 (U11, U17, U20, U22)
    // ===================================================================
    private static void userDirectoryLoad() throws Exception {
        section("UserDirectory - users.txt 읽기");
        Path dir = Files.createTempDirectory("coolmsg-users");

        UserDirectory empty = new UserDirectory(dir.resolve("none.txt"));
        empty.load();
        check("파일 없음 → 빈 목록 + 경고", empty.getUsers().isEmpty() && empty.getWarnings().size() == 1);

        UserDirectory base = newDirectory(dir, "base.txt");
        Path file = dir.resolve("mixed.txt");
        Files.copy(dir.resolve("base.txt"), file);
        append(file, "Kim|대문자|aa|bb\n"         // 아이디 형식 오류
                + "x|짧은아이디|aa|bb\n"            // 3자 미만
                + "lee|중복|aa|bb\n"                // 이미 있는 아이디
                + "park|칸부족|aa\n"                // 칸 수 오류
                + "choi|상태오류|aa|bb|gone\n"      // 상태 오류
                + "jung|정다은|aa|bb|deleted\r\n"); // 정상 (삭제됨, \r\n)
        UserDirectory mixed = new UserDirectory(file);
        mixed.load();
        List<String> ids = idsOf(mixed.getUsers());
        check("4칸(active)·5칸(deleted)·\\r\\n 줄을 읽고, 잘못된 줄 5개는 건너뜀",
                ids.equals(List.of("kim", "lee", "jung")) && mixed.find("jung").isDeleted()
                        && !mixed.find("kim").isDeleted());
        check("건너뛴 줄마다 경고 (5개)", mixed.getWarnings().size() == 5);
        check("목록은 등록 순서 그대로", idsOf(base.getUsers()).equals(List.of("kim", "lee")));
        expectThrows("getUsers()는 읽기 전용 복사본", UnsupportedOperationException.class,
                () -> base.getUsers().add(null));

        Path broken = dir.resolve("broken.txt");
        Files.write(broken, new byte[] {'k', 'i', 'm', '|', (byte) 0xC0, '\n'});
        expectThrows("UTF-8이 아닌 파일 → IOException (서버 시작 중단용, U22)", IOException.class,
                () -> new UserDirectory(broken).load());
    }

    // ===================================================================
    // UserDirectory 바꾸기 (REQ-F-204~206, 212, U17~U19)
    // ===================================================================
    private static void userDirectoryChange() throws Exception {
        section("UserDirectory - 추가·수정·초기화·삭제·복구");
        Path dir = Files.createTempDirectory("coolmsg-change");
        UserDirectory d = newDirectory(dir, "users.txt");

        UserAccount added = d.add("jung", "  정다은  ", "pass1".toCharArray());
        check("추가: 이름 앞뒤 공백 제거, 등록 순서 맨 뒤",
                added.getName().equals("정다은") && idsOf(d.getUsers()).equals(List.of("kim", "lee", "jung")));
        check("추가한 사용자로 로그인 가능", d.authenticate("jung", "pass1".toCharArray()));

        expectThrows("아이디 2자 → 거부", UserDirectoryException.class, () -> d.add("ab", "이름", pw()));
        expectThrows("아이디 21자 → 거부", UserDirectoryException.class, () -> d.add("a".repeat(21), "이름", pw()));
        expectThrows("아이디 대문자 → 거부", UserDirectoryException.class, () -> d.add("Hong", "이름", pw()));
        expectThrows("아이디 한글 → 거부", UserDirectoryException.class, () -> d.add("홍길동", "이름", pw()));
        expectThrows("이름 공백뿐 → 거부", UserDirectoryException.class, () -> d.add("hong", "   ", pw()));
        expectThrows("이름 21자 → 거부", UserDirectoryException.class, () -> d.add("hong", "가".repeat(21), pw()));
        expectThrows("이름에 '|' → 거부 (파일 칸이 깨지므로)", UserDirectoryException.class,
                () -> d.add("hong", "홍|길동", pw()));
        expectThrows("비밀번호 3자 → 거부", UserDirectoryException.class, () -> d.add("hong", "홍길동", "abc".toCharArray()));
        expectThrows("이미 있는 아이디 → 거부", UserDirectoryException.class, () -> d.add("kim", "다른 김", pw()));
        check("경계값 허용: 아이디 3자·20자, 이름 20자",
                d.add("abc", "가".repeat(20), pw()) != null && d.add("a".repeat(20), "이름", pw()) != null);
        expectThrows("두 비밀번호 다름 → 거부", UserDirectoryException.class,
                () -> UserDirectory.checkSame("pass1".toCharArray(), "pass2".toCharArray()));

        d.rename("kim", "김철수2");
        check("이름 수정", d.find("kim").getName().equals("김철수2"));
        expectThrows("이름 수정도 같은 규칙 ('|' 거부)", UserDirectoryException.class, () -> d.rename("kim", "a|b"));
        expectThrows("없는 아이디 수정 → 거부", UserDirectoryException.class, () -> d.rename("nobody", "이름"));

        d.resetPassword("lee", "newpass".toCharArray());
        check("비밀번호 초기화: 새 비밀번호만 통과",
                d.authenticate("lee", "newpass".toCharArray()) && !d.authenticate("lee", "pass".toCharArray()));

        d.delete("jung");
        check("삭제: 목록에는 남고 '삭제됨', 로그인 불가",
                d.find("jung").isDeleted() && !d.authenticate("jung", "pass1".toCharArray()));
        expectThrows("삭제한 아이디로 다시 추가 → 거부 (결정 14)", UserDirectoryException.class,
                () -> d.add("jung", "다른 사람", pw()));
        expectThrows("삭제된 사용자 이름 수정 → 거부", UserDirectoryException.class, () -> d.rename("jung", "이름"));
        expectThrows("삭제된 사용자 비밀번호 초기화 → 거부", UserDirectoryException.class,
                () -> d.resetPassword("jung", pw()));
        expectThrows("이미 삭제된 사용자 다시 삭제 → 거부", UserDirectoryException.class, () -> d.delete("jung"));

        int before = idsOf(d.getUsers()).indexOf("jung");
        d.restore("jung", "restored".toCharArray());
        check("복구: 활성, 새 비밀번호만 통과, 등록 순서 자리 그대로 (U18)",
                !d.find("jung").isDeleted() && d.authenticate("jung", "restored".toCharArray())
                        && !d.authenticate("jung", "pass1".toCharArray())
                        && idsOf(d.getUsers()).indexOf("jung") == before);
        expectThrows("삭제되지 않은 사용자 복구 → 거부", UserDirectoryException.class, () -> d.restore("kim", pw()));

        check("로그인 확인: 없는 아이디 → false", !d.authenticate("nobody", pw()));
        check("로그인 확인: 틀린 비밀번호 → false", !d.authenticate("kim", "wrong".toCharArray()));
    }

    // ===================================================================
    // UserDirectory 파일 저장 (U20)
    // ===================================================================
    private static void userDirectoryFile() throws Exception {
        section("UserDirectory - 저장, 주석 보존, 백업, 실패 시 되돌리기");
        Path dir = Files.createTempDirectory("coolmsg-file");
        UserDirectory d = newDirectory(dir, "users.txt");
        Path file = dir.resolve("users.txt");

        d.add("jung", "정다은", pw());
        d.delete("lee");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        check("맨 위 주석 블록 보존", lines.get(0).equals("# 사용자 목록 (테스트)") && lines.get(1).equals("# 두 번째 주석"));
        check("저장 형식: 5칸, 삭제는 deleted", lines.stream().anyMatch(l -> l.startsWith("lee|") && l.endsWith("|deleted"))
                && lines.stream().anyMatch(l -> l.startsWith("jung|") && l.endsWith("|active")));

        UserDirectory reloaded = new UserDirectory(file);
        reloaded.load();
        check("다시 읽으면 같은 목록·상태 (재실행해도 유지)",
                idsOf(reloaded.getUsers()).equals(List.of("kim", "lee", "jung")) && reloaded.find("lee").isDeleted()
                        && reloaded.authenticate("jung", pw()));
        check("오류 줄이 없으면 백업 파일을 만들지 않음", !Files.exists(dir.resolve("users.txt.bak")));

        // 형식 오류 줄이 있는 파일 → 첫 저장 직전에 .bak
        Path dir2 = Files.createTempDirectory("coolmsg-backup");
        newDirectory(dir2, "users.txt");
        Path file2 = dir2.resolve("users.txt");
        append(file2, "잘못된 줄\n");
        String original = new String(Files.readAllBytes(file2), StandardCharsets.UTF_8);
        UserDirectory d2 = new UserDirectory(file2);
        d2.load();
        d2.add("jung", "정다은", pw());
        Path bak = dir2.resolve("users.txt.bak");
        check("오류 줄이 있었으면 첫 저장 직전에 원래 파일을 .bak으로 백업",
                Files.exists(bak) && new String(Files.readAllBytes(bak), StandardCharsets.UTF_8).equals(original));
        d2.add("hong", "홍길동", pw());
        check("두 번째 저장에서는 백업을 덮어쓰지 않음",
                new String(Files.readAllBytes(bak), StandardCharsets.UTF_8).equals(original));

        // 저장 실패 → 메모리도 되돌림
        Path dir3 = Files.createTempDirectory("coolmsg-fail");
        UserDirectory d3 = newDirectory(dir3, "users.txt");
        Path file3 = dir3.resolve("users.txt");
        Files.delete(file3);
        Files.createDirectory(file3); // 같은 이름의 폴더를 만들어 파일로 바꿔 쓰지 못하게 함
        Files.writeString(file3.resolve("block.txt"), "x");
        expectThrows("저장 실패 → IOException", IOException.class, () -> d3.add("jung", "정다은", pw()));
        check("저장 실패하면 메모리 목록도 바꾸기 전으로 (U20)",
                d3.find("jung") == null && idsOf(d3.getUsers()).equals(List.of("kim", "lee")));
    }

    // ===================================================================
    // UserDirectory 동시 사용 (동시성 규칙 C8)
    // ===================================================================
    private static void userDirectoryConcurrency() throws Exception {
        section("UserDirectory - 여러 스레드가 동시에 추가 (C8)");
        Path dir = Files.createTempDirectory("coolmsg-concurrent");
        UserDirectory d = newDirectory(dir, "users.txt");

        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int tn = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < 5; i++) {
                    try {
                        d.add("user" + tn + "x" + i, "사용자" + tn + i, pw());
                    } catch (Exception e) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        UserDirectory reloaded = new UserDirectory(dir.resolve("users.txt"));
        reloaded.load();
        check("스레드 4개 × 5명 동시 추가 → 오류 없이 메모리 22명", errors.isEmpty() && d.getUsers().size() == 22);
        check("파일에도 22명 모두 저장 (덮어쓰기로 사라진 사람 없음)", reloaded.getUsers().size() == 22);
    }

    // ===================================================================
    // ServerContext
    // ===================================================================
    private static void serverContext() throws Exception {
        section("ServerContext - 시작할 때 설정 읽기");
        Path dir = Files.createTempDirectory("coolmsg-context");
        newDirectory(dir, "users.txt");
        Files.writeString(dir.resolve("server.txt"), "port=9200\nport=abc\n");
        ServerContext ctx = ServerContext.load(dir, new AdminConfig(dir.resolve("admin.txt")));
        check("설정·사용자 목록을 하나씩 만들어 들고 있음",
                ctx.getServerConfig().getPort() == 9000 && ctx.getUserDirectory().getUsers().size() == 2);
        check("경고에 파일 이름을 붙여 모음",
                ctx.getStartupWarnings().stream().allMatch(w -> w.startsWith("[server.txt]") || w.startsWith("[users.txt]"))
                        && ctx.getStartupWarnings().size() == 2);

        Path bad = Files.createTempDirectory("coolmsg-context-bad");
        Files.write(bad.resolve("users.txt"), new byte[] {(byte) 0xFF, (byte) 0xFE, '\n'});
        expectThrows("users.txt를 읽지 못하면 시작 실패 (U22)", IOException.class,
                () -> ServerContext.load(bad, new AdminConfig(bad.resolve("admin.txt"))));
    }

    // ===================================================================
    // 도우미
    // ===================================================================

    /** kim, lee 두 명과 주석 두 줄이 있는 users.txt를 만들어 읽는다. 비밀번호는 둘 다 "pass". */
    private static UserDirectory newDirectory(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, "# 사용자 목록 (테스트)\n# 두 번째 주석\n", StandardCharsets.UTF_8);
        UserDirectory tmp = new UserDirectory(file);
        tmp.load();
        tmp.add("kim", "김철수", "pass".toCharArray());
        tmp.add("lee", "이영희", "pass".toCharArray());
        UserDirectory d = new UserDirectory(file);
        d.load();
        return d;
    }

    private static char[] pw() {
        return "test1234".toCharArray();
    }

    private static List<String> idsOf(List<UserAccount> users) {
        List<String> ids = new ArrayList<>();
        for (UserAccount u : users) {
            ids.add(u.getId());
        }
        return ids;
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, new String(Files.readAllBytes(file), StandardCharsets.UTF_8) + text,
                StandardCharsets.UTF_8);
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("[" + name + "]");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    private static void expectThrows(String name, Class<? extends Throwable> expected, Action action) {
        try {
            action.run();
            check(name + "  (예외가 나지 않음)", false);
        } catch (Throwable t) {
            boolean ok = expected.isInstance(t);
            check(ok ? name : name + "  (다른 예외: " + t + ")", ok);
        }
    }
}
