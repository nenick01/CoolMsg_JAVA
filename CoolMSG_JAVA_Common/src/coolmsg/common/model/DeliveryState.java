package coolmsg.common.model;

/**
 * 받는 사람별 메시지 상태 (설계서 2.6).
 *
 * SENT(서버에 저장됨) → DELIVERED(받는 사람 PC에 도착) → READ(받는 사람이 열어 봄)
 * 상태는 이 순서로 앞으로만 바뀐다. 그래서 같은 알림이 두 번 오거나 순서가 뒤바뀌어도
 * "더 앞선 상태일 때만 반영"하면 결과가 같다.
 */
public enum DeliveryState {

    SENT,
    DELIVERED,
    READ;

    /** 이 상태가 other보다 더 앞선(진행된) 상태인가. */
    public boolean isAfter(DeliveryState other) {
        return other == null || this.ordinal() > other.ordinal();
    }

    /** 이름으로 찾는다. 모르는 이름이면 null. */
    public static DeliveryState fromName(String name) {
        for (DeliveryState state : values()) {
            if (state.name().equals(name)) {
                return state;
            }
        }
        return null;
    }
}
