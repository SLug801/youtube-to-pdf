@echo off
cd /d %~dp0

if not exist "target\youtube-to-pdf-1.0.0-shaded.jar" (
    echo JAR 파일이 없습니다. build.bat 를 먼저 실행해주세요.
    pause
    exit /b 1
)

java -Djna.nosys=true -Djna.protected=true -Dfile.encoding=UTF-8 -jar "target\youtube-to-pdf-1.0.0-shaded.jar" %*
