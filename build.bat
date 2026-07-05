@echo off
echo Gradle 빌드 시작...
call gradlew.bat shadowJar
if %ERRORLEVEL% == 0 (
    echo 빌드 성공: build\libs\youtube-to-pdf-1.0.0-shaded.jar
) else (
    echo 빌드 실패. 위 오류를 확인해주세요.
)
pause
