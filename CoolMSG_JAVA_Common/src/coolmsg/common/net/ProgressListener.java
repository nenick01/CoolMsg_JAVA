package coolmsg.common.net;

/**
 * 파일 전송 진행률 알림 (설계서 2.12, 제출 후 확장).
 * 통신 코드가 화면(JProgressBar)을 몰라도 진행률을 알릴 수 있게 하는 약속.
 */
public interface ProgressListener {

    /**
     * @param done  지금까지 보낸(받은) 바이트 수
     * @param total 전체 바이트 수
     */
    void onProgress(long done, long total);
}
