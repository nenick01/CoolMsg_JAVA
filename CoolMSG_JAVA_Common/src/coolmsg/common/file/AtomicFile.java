package coolmsg.common.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * "반쯤 쓴 파일"이 남지 않게 저장하는 도구.
 *
 * 원래 파일에 바로 쓰다가 프로그램이 꺼지면, 파일 앞부분만 남아 내용이 망가진다.
 * 그래서 같은 폴더의 임시 파일에 끝까지 쓴 뒤, 이름을 바꿔(move) 원래 파일을 교체한다.
 * 이름 바꾸기는 한 번에 일어나므로, 다른 사람이 보는 파일은 항상 "옛 내용 전체" 아니면 "새 내용 전체"다.
 */
public final class AtomicFile {

    private AtomicFile() {
    }

    /** 바이트를 원자적으로 저장한다. 폴더가 없으면 만든다. */
    public static void write(Path target, byte[] content) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path dir = absolute.getParent();
        Files.createDirectories(dir);
        // 같은 폴더에 만들어야 이름 바꾸기가 한 번에 일어난다 (다른 드라이브면 복사가 된다).
        Path temp = Files.createTempFile(dir, absolute.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temp, content);
            move(temp, absolute);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** 문자열을 UTF-8로 원자적으로 저장한다. */
    public static void writeText(Path target, String text) throws IOException {
        write(target, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 파일 시스템이 원자적 이동을 지원하지 않으면 일반 교체로 대신한다.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
