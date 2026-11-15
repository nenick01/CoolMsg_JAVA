# CoolMsg_JAVA

Java Swing 기반 **클라이언트–서버 구조 사내 메신저**입니다.
수신자가 접속해 있지 않아도 메시지를 보낼 수 있고, 수신자가 나중에 접속하면 저장된 메시지를 받아 볼 수 있습니다(저장 후 전달, Store-and-Forward).

## 주요 기능 (개발 중)

| 구분 | 기능 | 상태 |
|---|---|---|
| 서버 관리 화면 | 관리자 로그인(처음 실행 시 비밀번호 설정) | 동작 |
| | 사용자 관리: 추가·조회·이름 수정·비밀번호 초기화·삭제·복구 | 동작 |
| | 서버 시작·중지, 접속자·로그 표시 | 화면만 |
| | 조직도 편집 | 예정 |
| 메시지 | 여러 명에게 보내기(참조 포함), 오프라인 사용자에게 저장 후 전달 | 예정 |
| | 받은·보낸 내역 조회·삭제, 읽음 확인, 접속 상태 표시 | 예정 |
| | 서식 있는 본문(글자 크기·색), 회신·전체회신 | 예정 |

- 첨부파일은 확장할 수 있도록 설계 뼈대만 둡니다(제출 후 확장).
- 기능별 요구사항과 제출 범위는 [요구사항분석서 9장](docs/02_요구사항분석서.md)에 있습니다.

## 구조

```
CoolMsg_JAVA/                  (Eclipse 워크스페이스 겸 VS Code 작업 폴더)
├─ docs/                       기획·설계 문서
├─ CoolMSG_JAVA_Common/        서버·클라이언트 공용 코드 (패킷, 프로토콜 상수)
│  └─ src/coolmsg/common/
├─ CoolMSG_JAVA_Server/        서버 프로그램  → Common 참조
│  ├─ src/coolmsg/server/
│  ├─ config/                  관리자 설정 (users.txt, org.txt는 Git에 포함, admin.txt는 제외)
│  └─ data/                    실행 중 생기는 메시지·첨부파일 — Git 제외
├─ CoolMSG_JAVA_Client/        Swing 클라이언트 → Common 참조
│  ├─ src/
│  └─ data/                    PC의 보낸 내역·받은 내역 — Git 제외
└─ .vscode/launch.json         VS Code 실행·디버그 설정 (서버, 클라이언트)
```

- 세 프로젝트 모두 Eclipse 프로젝트 형식(`.project` / `.classpath`)이고 JDK 17을 사용합니다.
- Server와 Client는 **Common 프로젝트를 참조**해 같은 패킷·프로토콜 코드를 씁니다. Common을 고치면 양쪽에 함께 반영됩니다.

## 실행 방법

- **VS Code**: 저장소 루트 폴더를 열고 `Run and Debug`에서 `Server: ServerMain` 또는 `Client: MyFrame` 선택 후 F5
  - 서버를 먼저 실행한 뒤 클라이언트를 실행합니다. 클라이언트는 여러 개 띄울 수 있습니다.
  - 서버는 관리 화면(GUI)으로 열립니다. 처음 실행하면 관리자 비밀번호를 정하는 창이 뜨고, 이 값은 `CoolMSG_JAVA_Server/config/admin.txt`에 해시로 저장됩니다(Git 제외). 잊으면 그 파일을 지우고 다시 실행하세요.
  - 예시 사용자(kim, lee, park, choi)의 테스트 비밀번호는 `CoolMSG_JAVA_Server/config/users.txt` 맨 위 주석에 있습니다. 관리 화면에서 사용자를 바꿔도 이 주석은 보존됩니다.
  - 포트·서버 이름 등은 `CoolMSG_JAVA_Server/config/server.txt`에서 바꿉니다(서버를 다시 켜야 반영).
- **Eclipse**: 저장소 루트를 워크스페이스로 열고 `File → Import → Existing Projects into Workspace`로 세 프로젝트를 모두 가져오기
  - Common을 빼고 가져오면 Server·Client에 빌드 오류가 납니다.

## 테스트

- VS Code: `Run and Debug`에서 `Test: Common` 또는 `Test: Server` 선택 후 F5
- Eclipse: `CoolMSG_JAVA_Common/test/coolmsg/common/CommonTest.java` 또는 `CoolMSG_JAVA_Server/test/coolmsg/server/ServerTest.java` → Run As → Java Application
- 결과는 줄마다 `PASS`/`FAIL`로 나오고, 마지막 줄에 통과·실패 개수가 나옵니다. 기록은 [테스트 기록](docs/04_테스트기록.md)에 있습니다.

## 문서

| 문서 | 내용 |
|---|---|
| [제안서](docs/01_제안서.md) | 기획 의도, 아키텍처 선택 근거, 일정 |
| [요구사항분석서](docs/02_요구사항분석서.md) | 기능·비기능 요구사항, 사용 시나리오 |
| [설계서](docs/03_설계서.md) | 시스템 구성, 통신 프로토콜, 클래스 설계 |
| [테스트 기록](docs/04_테스트기록.md) | 정상 동작과 오류·예외 처리 확인 결과 |
| [개발 일지](docs/devlog.md) | 날짜별 진행 상황과 문제 해결 기록 |

## 개발 환경

- Java, Swing
- TCP 소켓 통신
- VS Code / Eclipse
