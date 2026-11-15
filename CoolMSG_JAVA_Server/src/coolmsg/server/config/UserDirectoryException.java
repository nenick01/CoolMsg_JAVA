package coolmsg.server.config;

/**
 * 사용자 관리 규칙을 어겼을 때의 예외.  설계서 3.5, 3.7
 * (아이디·이름·비밀번호 형식, 아이디 중복, 없는 아이디, 삭제된 사용자에 대한 수정 등)
 *
 * 파일 오류(IOException)와 따로 두어, 관리 화면이 "입력을 고쳐 주세요"와 "저장에 실패했습니다"를 구분해 안내한다.
 * 메시지는 그대로 화면에 보여 줄 수 있는 문장으로 쓴다.
 */
public class UserDirectoryException extends Exception {

    private static final long serialVersionUID = 1L;

    public UserDirectoryException(String message) {
        super(message);
    }
}
