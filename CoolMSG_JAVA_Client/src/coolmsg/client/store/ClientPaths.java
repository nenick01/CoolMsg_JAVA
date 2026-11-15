package coolmsg.client.store;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 클라이언트 저장 위치를 한 곳에서 정한다 (설계서 4.1, 5장 U34).
 *
 * 사용자 홈 폴더 아래 .coolmsg/ 에 저장한다. 예: C:\Users\홍길동\.coolmsg\
 *   client.txt        마지막 서버 주소·아이디
 *   {아이디}/inbox/    받은 내역 (구현 4단계)
 *   {아이디}/sent/     보낸 내역 (구현 4단계)
 *
 * 실행 폴더 기준(data/)으로 두면 어디서 실행하느냐(IDE, 바탕화면의 JAR 등)에 따라 위치가 바뀐다.
 * 홈 폴더는 실행 위치와 상관없이 항상 같다. 테스트에서는 다른 기준 폴더를 넣어 쓸 수 있다.
 */
public final class ClientPaths {

    public static final String FOLDER_NAME = ".coolmsg";

    private final Path base;

    /** 테스트 등에서 기준 폴더를 직접 정할 때. */
    public ClientPaths(Path base) {
        this.base = base;
    }

    /** 사용자 홈 폴더의 .coolmsg/ (실제 실행용). */
    public static ClientPaths inUserHome() {
        return new ClientPaths(Paths.get(System.getProperty("user.home"), FOLDER_NAME));
    }

    public Path base() {
        return base;
    }

    /** 마지막 서버 주소·아이디 파일. */
    public Path settingsFile() {
        return base.resolve("client.txt");
    }

    /** 그 아이디의 보관함 폴더 (구현 4단계). 아이디별로 나눠 한 PC에서 여러 아이디로 테스트해도 섞이지 않는다. */
    public Path mailboxDir(String userId) {
        return base.resolve(userId);
    }
}
