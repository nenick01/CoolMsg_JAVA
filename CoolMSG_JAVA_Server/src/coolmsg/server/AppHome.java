package coolmsg.server;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 서버의 기준 폴더 = config/·data/가 놓이는 곳 (설계서 5장 U63).
 *
 *   jpackage로 만든 exe로 실행  → exe가 있는 폴더 (jpackage 실행기가 jpackage.app-path로 exe 경로를 알려 준다.
 *                                  jar는 그 아래 app/ 폴더에 있으므로 jar 위치를 쓰면 안 된다)
 *   java -jar 로 실행            → jar가 있는 폴더
 *   IDE에서 클래스 폴더로 실행    → 실행 폴더(작업 폴더). 개발 중 쓰던 프로젝트의 config/·data/를 그대로 쓴다
 *
 * 그래서 서버 폴더째 옮기면 설정과 데이터가 함께 간다. 바로가기나 다른 폴더에서 실행해도 위치가 바뀌지 않는다.
 */
public final class AppHome {

    /** jpackage 실행기가 넣어 주는 시스템 속성 (exe의 전체 경로). */
    public static final String JPACKAGE_APP_PATH = "jpackage.app-path";

    private AppHome() {
    }

    /** 지금 실행 중인 서버의 기준 폴더. */
    public static Path resolve() {
        Path codeSource = null;
        try {
            codeSource = Paths.get(AppHome.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            // 알 수 없으면 작업 폴더
        }
        return resolve(System.getProperty(JPACKAGE_APP_PATH), codeSource, Paths.get("").toAbsolutePath());
    }

    /**
     * 기준 폴더를 고르는 규칙 (테스트할 수 있게 입력을 받는다).
     *
     * @param jpackageAppPath jpackage.app-path 값 (없으면 null)
     * @param codeSource      이 클래스가 들어 있는 jar 파일 또는 클래스 폴더 (모르면 null)
     * @param workingDir      작업 폴더
     */
    static Path resolve(String jpackageAppPath, Path codeSource, Path workingDir) {
        if (jpackageAppPath != null && !jpackageAppPath.isBlank()) {
            Path parent = Paths.get(jpackageAppPath).toAbsolutePath().getParent();
            if (parent != null) {
                return parent;
            }
        }
        if (codeSource != null && codeSource.getFileName() != null
                && codeSource.getFileName().toString().toLowerCase().endsWith(".jar")
                && !Files.isDirectory(codeSource)) {
            Path parent = codeSource.toAbsolutePath().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return workingDir;
    }
}
