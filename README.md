# CoolMsg_JAVA

Java Swing 기반 **클라이언트–서버 구조 사내 메신저**입니다.
수신자가 접속해 있지 않아도 메시지를 보낼 수 있고, 수신자가 나중에 접속하면 저장된 메시지를 받아 볼 수 있습니다(저장 후 전달, Store-and-Forward).

## 주요 기능

| 구분 | 기능 | 상태 |
|---|---|---|
| 서버 관리 화면 | 관리자 로그인(처음 실행 시 비밀번호 설정) | 동작 |
| | 사용자 관리: 추가·조회·이름 수정·비밀번호 초기화·삭제·복구 | 동작 |
| | 서버 시작·중지(관리 화면이 열리면 자동 시작), 연결 로그 | 동작 |
| | 접속자 목록, 사용자 표의 '접속 중' 표시 | 동작 |
| | 조직도 편집(부서 추가·이름 변경·삭제·순서, 사용자 배치, 저장 즉시 반영), 사용자 표의 소속 부서 | 동작 |
| 클라이언트 | 로그인 화면, 서버 연결 테스트(서버 이름·버전·응답 시간, 실패 원인) | 동작 |
| | 로그인·로그아웃, 연결이 끊기면 이유를 보여 주고 로그인 화면으로 | 동작 |
| | 체크박스 조직도(부서를 체크하면 그 부서 모두), 조직도를 못 받으면 등록 순서로 임시 표시 | 동작 |
| 메시지 | 여러 명에게 보내기, 오프라인 사용자에게 저장 후 전달(서버를 다시 켜도 유지) | 동작 |
| | 메시지 관리함(받은·보낸 탭, 최신순, 안 읽음 굵게), 보기·삭제, 새 메시지 알림 창 | 동작 |
| | 접속 상태 실시간 표시(●/○), 읽음 확인(목록 요약·받는 사람별 읽은 시각), 읽음 알림 창 | 동작 |
| | 참조 | 동작 |
| | 서식 있는 본문(글꼴·크기·굵게·기울임·밑줄·취소선·글자 색), 회신·전체회신(원본 인용) | 동작 |

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
│  ├─ config/                  설정 (server.txt만 Git에 포함, users.txt·org.txt·admin.txt는 제외)
│  │  └─ sample/              예시 users.txt·org.txt (처음 실행 때 config/로 복사됨)
│  └─ data/                    실행 중 생기는 메시지·첨부파일 — Git 제외
├─ CoolMSG_JAVA_Client/        Swing 클라이언트 → Common 참조
│  └─ src/                     (보낸·받은 내역은 사용자 홈 폴더의 .coolmsg/에 저장)
├─ build.bat                   배포 파일 만들기 (jar, bat, exe 폴더 zip → dist/)
└─ .vscode/launch.json         VS Code 실행·디버그 설정 (서버, 클라이언트)
```

- 세 프로젝트 모두 Eclipse 프로젝트 형식(`.project` / `.classpath`)이고 JDK 17을 사용합니다.
- Server와 Client는 **Common 프로젝트를 참조**해 같은 패킷·프로토콜 코드를 씁니다. Common을 고치면 양쪽에 함께 반영됩니다.

## 실행 방법

서버를 먼저 켜고, 클라이언트를 켜서 로그인합니다. 클라이언트는 여러 개 띄울 수 있습니다(같은 PC에서도 가능).

### 1. 바로 실행 — Releases에서 내려받기

저장소 오른쪽 **[Releases](https://github.com/nenick01/CoolMsg_JAVA/releases)**에서 받습니다.

| 파일 | 내용 | 필요한 것 |
|---|---|---|
| `CoolMsg-Server-exe.zip` / `CoolMsg-Client-exe.zip` | `CoolMsg-Server.exe` / `CoolMsg-Client.exe` (자바 실행 환경 포함) | 없음 (Windows) |
| `CoolMsg-Server-jar.zip` / `CoolMsg-Client-jar.zip` | jar + `run-server.bat` / `run-client.bat` | **Java 17 이상** |

1. zip을 원하는 폴더에 풉니다(설치 과정 없음).
2. 서버: `CoolMsg-Server.exe` 또는 `run-server.bat`을 더블클릭합니다. (명령어: `java -jar CoolMsg-Server.jar`)
3. 클라이언트: `CoolMsg-Client.exe` 또는 `run-client.bat`을 더블클릭합니다. (명령어: `java -jar CoolMsg-Client.jar`)
4. 로그인 화면의 서버 칸에 `localhost`(같은 PC) 또는 서버 PC의 IP를 쓰고, 예시 사용자로 로그인합니다(아래 "처음 실행할 때 알아 둘 것").

- 서버의 설정·데이터(`config/`, `data/`)는 **서버 exe·jar가 있는 폴더**에 생깁니다. 서버 폴더째 옮기면 사용자 목록과 메시지도 함께 옮겨집니다.

### 2. 소스에서 빌드해서 실행

**Windows** — 저장소 맨 위 폴더에서:

```
build.bat          jar·bat·exe 폴더를 모두 만들어 dist\ 에 zip으로 (jpackage 사용, 1분 안팎)
build.bat jar      exe 없이 jar와 bat만
```

**명령어로 직접** (JDK 17 이상, 운영체제 상관없음) — 저장소 맨 위 폴더에서:

```
# 1) 컴파일 (-sourcepath의 경로 구분: Windows는 ;  macOS·Linux는 :)
javac -encoding UTF-8 -d build/server -sourcepath "CoolMSG_JAVA_Common/src;CoolMSG_JAVA_Server/src" CoolMSG_JAVA_Server/src/coolmsg/server/ServerMain.java
javac -encoding UTF-8 -d build/client -sourcepath "CoolMSG_JAVA_Common/src;CoolMSG_JAVA_Client/src" CoolMSG_JAVA_Client/src/coolmsg/client/ClientMain.java

# 2) 실행 가능 jar 만들기
jar --create --file CoolMsg-Server.jar --main-class coolmsg.server.ServerMain -C build/server .
jar --create --file CoolMsg-Client.jar --main-class coolmsg.client.ClientMain -C build/client .

# 3) 실행 — 서버 jar 옆에 config/가 있어야 예시 사용자로 로그인할 수 있으므로 설정 예시를 함께 복사
mkdir server-run
cp CoolMsg-Server.jar server-run/
cp -r CoolMSG_JAVA_Server/config server-run/
java -jar server-run/CoolMsg-Server.jar
java -jar CoolMsg-Client.jar
```

- Windows 명령 프롬프트에서는 `cp` 대신 `copy`, `cp -r` 대신 `xcopy /e /i`를 씁니다. `build.bat`이 이 과정을 대신해 줍니다.

### 3. IDE에서 실행 (개발용)

- **VS Code**: 저장소 루트 폴더를 열고 `Run and Debug`에서 `Server: ServerMain` 또는 `Client: ClientMain` 선택 후 F5
- **Eclipse**: 저장소 루트를 워크스페이스로 열고 `File → Import → Existing Projects into Workspace`로 세 프로젝트를 모두 가져온 뒤 `ServerMain`·`ClientMain` → Run As → Java Application
  - Common을 빼고 가져오면 Server·Client에 빌드 오류가 납니다.
- IDE에서 실행하면 서버의 설정·데이터는 `CoolMSG_JAVA_Server/config/`, `CoolMSG_JAVA_Server/data/`를 씁니다.

### 처음 실행할 때 알아 둘 것

- **관리자 비밀번호**: 서버를 처음 켜면 관리자 비밀번호를 정하는 창이 뜹니다(4자 이상). `config/admin.txt`에 해시로 저장되며, 잊으면 그 파일을 지우고 다시 실행합니다.
- **예시 사용자**: 처음 실행하면 `config/sample/`의 예시 사용자 목록·조직도가 `config/`로 복사됩니다. 예시 사용자(kim, lee, park, choi)의 테스트 비밀번호는 `config/sample/users.txt` 맨 위 주석에 있습니다. 사용자는 서버 관리 화면의 [사용자 관리]에서 추가합니다(회원가입 없음).
- **방화벽**: 서버를 처음 켤 때 Windows 방화벽 허용 창이 뜨면, 다른 PC에서 접속하려면 허용해야 합니다(같은 PC의 `localhost`는 허용하지 않아도 됨).
- **서버 주소**: 로그인 화면의 서버 칸에는 `localhost` 또는 서버 PC의 IP를 씁니다. 포트를 바꿨다면 `IP:포트`. [연결 테스트]로 접속 가능 여부를 확인할 수 있습니다.
- **포트·서버 이름**: `config/server.txt`에서 바꿉니다(서버를 다시 켜야 반영, 기본 포트 9000).
- **저장 위치**: 서버의 메시지와 미전달 목록은 `data/`(`messages/`, `pending/`, `receipts/`). 클라이언트는 서버 주소·아이디와 보관함을 **사용자 홈 폴더의 `.coolmsg/`**에 저장합니다(예: `C:\Users\홍길동\.coolmsg\`). 비밀번호는 저장하지 않습니다.
- 운영 데이터(`users.txt`, `org.txt`, `admin.txt`, `data/`)는 Git에 올라가지 않습니다.

## 테스트

- VS Code: `Run and Debug`에서 `Test: Common`, `Test: Server`, `Test: Client` 중 선택 후 F5
- Eclipse: 각 프로젝트의 `test/` 폴더에 있는 `CommonTest`, `ServerTest`, `ClientTest` → Run As → Java Application
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

- Java 17 (JDK), Swing — 외부 라이브러리 없음
- TCP 소켓 통신 (직접 정의한 응용 계층 프로토콜, 설계서 2장)
- VS Code / Eclipse
- 배포: `jar`, `jpackage` (JDK 기본 도구)
