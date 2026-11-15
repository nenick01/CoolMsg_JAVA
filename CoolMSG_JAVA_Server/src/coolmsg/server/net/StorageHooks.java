package coolmsg.server.net;

import java.io.IOException;

/**
 * 서버 시작·중지에 맞춰 저장소를 열고 닫는 약속 (설계서 3.8.6).
 *
 * ServerListener(net)는 저장소(service·store)를 모른다. ServerContext가
 * MessageService의 openStorage/closeStorage를 이 약속으로 이어 준다.
 */
public interface StorageHooks {

    /** 접속을 받기 전에 부른다 (색인 확인·복구). 실패하면 서버를 시작하지 않는다. */
    void open() throws IOException;

    /** 모든 연결이 닫힌 뒤에 부른다 (정상 종료 표시). */
    void close();

    /** 아무것도 하지 않는 기본값 (저장소가 필요 없는 테스트용). */
    StorageHooks NONE = new StorageHooks() {
        @Override
        public void open() {
        }

        @Override
        public void close() {
        }
    };
}
