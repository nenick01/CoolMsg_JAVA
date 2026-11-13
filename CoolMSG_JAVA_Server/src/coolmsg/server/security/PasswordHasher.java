package coolmsg.server.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 비밀번호를 원문 대신 "솔트 + 해시"로 저장하기 위한 도구.
 *
 * - 솔트: 사용자마다 다른 무작위 값. 같은 비밀번호라도 해시가 달라진다.
 * - PBKDF2: 일부러 느리게 만든 해시. 파일이 유출돼도 비밀번호를 하나씩 대입해 보기 어렵다.
 * - Java 기본 라이브러리(javax.crypto)만 사용한다.
 *
 * 자세한 내용은 docs/03_설계서.md 3.2 "비밀번호 저장" 참고.
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 65536;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** 새 솔트를 16진수 문자열로 만든다. */
    public static String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return toHex(salt);
    }

    /** 솔트와 비밀번호로 해시(16진수 문자열)를 만든다. */
    public static String hash(String saltHex, char[] password) {
        PBEKeySpec spec = new PBEKeySpec(password, fromHex(saltHex), ITERATIONS, KEY_BITS);
        try {
            byte[] hash = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            return toHex(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("비밀번호 해시를 만들 수 없습니다.", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 입력한 비밀번호가 저장된 해시와 같은지 확인한다. */
    public static boolean matches(String saltHex, String expectedHashHex, char[] password) {
        byte[] actual = fromHex(hash(saltHex, password));
        byte[] expected = fromHex(expectedHashHex);
        // 비교에 걸리는 시간으로 정답을 추측하지 못하도록, 처음부터 끝까지 비교하는 메서드를 쓴다.
        return MessageDigest.isEqual(actual, expected);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
