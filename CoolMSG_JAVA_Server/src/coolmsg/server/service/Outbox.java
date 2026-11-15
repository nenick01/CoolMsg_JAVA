package coolmsg.server.service;

import coolmsg.common.net.Packet;

/**
 * 서비스가 "누군가에게 패킷을 보내 달라"고 부탁하는 약속 (설계서 3.1 Outbox).
 *
 * service는 소켓을 모른다. 실제로 보내는 일은 net의 SessionRegistry가 이 약속을 구현해서 한다.
 * 그래서 net → service → net 순환이 생기지 않고, 테스트에서는 보낸 패킷을 모아 두는 가짜로 바꿔 끼울 수 있다.
 */
public interface Outbox {

    /** 지금 로그인해 있는가. */
    boolean isOnline(String userId);

    /** 그 사람이 접속 중이면 보내기 대기열에 넣는다. 접속 중이 아니거나 넣지 못하면 false. 기다리지 않는다. */
    boolean send(String userId, Packet packet);

    /**
     * 밀린 메시지 보내기용(U36): 그 사람의 보내기 대기열이 절반 이상 차 있으면 줄어들 때까지 기다렸다가 넣는다.
     * 기다리는 동안 연결이 끊기면 false.
     */
    boolean sendWhenRoom(String userId, Packet packet) throws InterruptedException;
}
