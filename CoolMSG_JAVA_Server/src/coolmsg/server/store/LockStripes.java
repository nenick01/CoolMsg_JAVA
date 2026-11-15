package coolmsg.server.store;

/**
 * 키(메시지 ID, 아이디)별 잠금 (설계서 3.8.4, 5장 U16).
 *
 * 키마다 잠금 객체를 새로 만들면 메시지 수만큼 계속 늘어나 지워지지 않는다.
 * 그래서 잠금 64개를 미리 만들어 두고 "키의 해시값 % 64"로 하나를 고른다.
 * 서로 다른 두 키가 같은 잠금에 걸리면 잠깐 줄을 설 뿐, 결과가 틀리지는 않는다.
 *
 * 사용: synchronized (stripes.lockFor(messageId)) { 읽기 → 고치기 → 쓰기 }
 */
public final class LockStripes {

    public static final int DEFAULT_SIZE = 64;

    private final Object[] locks;

    public LockStripes() {
        this(DEFAULT_SIZE);
    }

    public LockStripes(int size) {
        locks = new Object[size];
        for (int i = 0; i < size; i++) {
            locks[i] = new Object();
        }
    }

    /** 이 키에 해당하는 잠금 객체. 같은 키는 항상 같은 잠금을 받는다. */
    public Object lockFor(String key) {
        int h = key.hashCode();
        h ^= (h >>> 16); // 위쪽 비트도 섞는다 (HashMap과 같은 방법). 아래쪽 비트만 쓰면 비슷한 키가 몰린다
        return locks[Math.floorMod(h, locks.length)];
    }
}
