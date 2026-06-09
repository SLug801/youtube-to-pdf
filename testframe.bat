@echo off
REM Transparent single-frame test: compile, then process one image.
REM Usage:  testframe.bat <image>    e.g.  testframe.bat test_frame.png
echo [1/2] compiling...
call mvnw.cmd -q compile || goto :fail
echo [2/2] processing one frame...
java -cp "target\classes;target\youtube-to-pdf-1.0.0-shaded.jar" com.sheetmusic.TransparentTest %1
goto :eof
:fail
echo compile failed.
exit /b 1
