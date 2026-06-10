@echo off
REM ============================================================
REM  Build a self-contained Windows app (bundled JRE + yt-dlp).
REM  Output: dist\youtube-to-pdf\  (zip this folder and share)
REM  User just unzips and runs youtube-to-pdf.exe . No Java needed.
REM ============================================================
setlocal
set JAR=youtube-to-pdf-1.0.0-shaded.jar
set STAGE=dist-input
set OUT=dist

echo [1/4] Building jar...
call mvnw.cmd -q package -DskipTests
if errorlevel 1 goto :fail

echo [2/4] Staging app files (jar + yt-dlp.exe)...
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%"
copy /y "target\%JAR%" "%STAGE%\" >nul
if not exist "yt-dlp.exe" (
  echo   ERROR: yt-dlp.exe not found in project root.
  goto :fail
)
copy /y "yt-dlp.exe" "%STAGE%\" >nul

echo [3/4] Running jpackage (this bundles a JRE, takes a minute)...
if exist "%OUT%\youtube-to-pdf" rmdir /s /q "%OUT%\youtube-to-pdf"
jpackage ^
  --type app-image ^
  --name youtube-to-pdf ^
  --input "%STAGE%" ^
  --main-jar %JAR% ^
  --main-class com.sheetmusic.Main ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "-Djna.nosys=true" ^
  --java-options "-Djna.protected=true" ^
  --dest "%OUT%"
if errorlevel 1 goto :fail

echo [4/4] Cleaning up staging...
rmdir /s /q "%STAGE%"

echo.
echo DONE.
echo   App : %OUT%\youtube-to-pdf\youtube-to-pdf.exe
echo   Share: zip the folder  %OUT%\youtube-to-pdf  and send it.
echo          Recipient unzips and double-clicks youtube-to-pdf.exe (no install).
goto :eof

:fail
echo.
echo BUILD FAILED. See messages above.
exit /b 1
