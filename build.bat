@echo off
chcp 65001 >nul
rem ============================================================================
rem  CoolMsg 배포 파일 만들기 (설계서 5장 U63)
rem  필요: JDK 17 이상 (javac, jar, jpackage가 PATH에 있어야 함)
rem  결과: dist\ 폴더
rem    CoolMsg-Server-jar.zip   서버 jar + run-server.bat + config\  (자바 17 설치 필요)
rem    CoolMsg-Client-jar.zip   클라이언트 jar + run-client.bat       (자바 17 설치 필요)
rem    CoolMsg-Server-exe.zip   서버 exe 폴더 (자바 포함, 설치 불필요)
rem    CoolMsg-Client-exe.zip   클라이언트 exe 폴더 (자바 포함, 설치 불필요)
rem  exe 없이 jar만 만들려면:  build.bat jar
rem ============================================================================
setlocal
cd /d "%~dp0"

where javac >nul 2>nul || (echo [오류] javac가 없습니다. JDK 17 이상을 설치하고 PATH에 추가하세요.& exit /b 1)

echo [1/5] 이전 결과 지우기
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build dist

echo [2/5] 컴파일 (Common + Server, Common + Client)
javac -encoding UTF-8 -d build\server -sourcepath CoolMSG_JAVA_Common\src;CoolMSG_JAVA_Server\src CoolMSG_JAVA_Server\src\coolmsg\server\ServerMain.java || exit /b 1
javac -encoding UTF-8 -d build\client -sourcepath CoolMSG_JAVA_Common\src;CoolMSG_JAVA_Client\src CoolMSG_JAVA_Client\src\coolmsg\client\ClientMain.java || exit /b 1

echo [3/5] 실행 가능 jar 만들기
mkdir build\jar-server build\jar-client
jar --create --file build\jar-server\CoolMsg-Server.jar --main-class coolmsg.server.ServerMain -C build\server . || exit /b 1
jar --create --file build\jar-client\CoolMsg-Client.jar --main-class coolmsg.client.ClientMain -C build\client . || exit /b 1

echo [4/5] jar 배포 폴더 (jar + bat + 설정 예시)
set S=dist\CoolMsg-Server
set C=dist\CoolMsg-Client
mkdir %S%\config\sample %C%
copy /y build\jar-server\CoolMsg-Server.jar %S%\ >nul
copy /y build\jar-client\CoolMsg-Client.jar %C%\ >nul
copy /y CoolMSG_JAVA_Server\config\server.txt %S%\config\ >nul
copy /y CoolMSG_JAVA_Server\config\sample\*.txt %S%\config\sample\ >nul
(echo @echo off& echo cd /d "%%~dp0"& echo start "" javaw -Dfile.encoding=UTF-8 -jar CoolMsg-Server.jar) > %S%\run-server.bat
(echo @echo off& echo cd /d "%%~dp0"& echo start "" javaw -Dfile.encoding=UTF-8 -jar CoolMsg-Client.jar) > %C%\run-client.bat
rem zip은 JDK의 jar 도구로 만든다 (-M: 매니페스트 없는 일반 zip). PowerShell 압축은 백신이 파일을 잠깐 잡으면 실패한다
jar -c -M -f dist\CoolMsg-Server-jar.zip -C dist CoolMsg-Server || exit /b 1
jar -c -M -f dist\CoolMsg-Client-jar.zip -C dist CoolMsg-Client || exit /b 1

if /i "%~1"=="jar" (
    echo [5/5] exe는 건너뜀 ^(build.bat jar^)
    goto done
)

echo [5/5] exe 폴더 만들기 (jpackage, 자바 실행 환경 포함 - 1~2분 걸림)
where jpackage >nul 2>nul || (echo [오류] jpackage가 없습니다. JDK 17 이상인지 확인하세요.& exit /b 1)
jpackage --type app-image --name CoolMsg-Server --app-version 1.0.0 --input build\jar-server --main-jar CoolMsg-Server.jar --main-class coolmsg.server.ServerMain --add-modules java.base,java.desktop --java-options -Dfile.encoding=UTF-8 --dest build\image || exit /b 1
jpackage --type app-image --name CoolMsg-Client --app-version 1.0.0 --input build\jar-client --main-jar CoolMsg-Client.jar --main-class coolmsg.client.ClientMain --add-modules java.base,java.desktop --java-options -Dfile.encoding=UTF-8 --dest build\image || exit /b 1
rem 서버 설정은 exe 옆에 (AppHome: exe 폴더가 기준)
mkdir build\image\CoolMsg-Server\config\sample
copy /y CoolMSG_JAVA_Server\config\server.txt build\image\CoolMsg-Server\config\ >nul
copy /y CoolMSG_JAVA_Server\config\sample\*.txt build\image\CoolMsg-Server\config\sample\ >nul
jar -c -M -f dist\CoolMsg-Server-exe.zip -C build\image CoolMsg-Server || exit /b 1
jar -c -M -f dist\CoolMsg-Client-exe.zip -C build\image CoolMsg-Client || exit /b 1

:done
echo.
echo 완료: dist\ 폴더
dir /b dist
endlocal
