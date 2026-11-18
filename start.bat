@echo off
REM 用法：start.bat                 搜尋專案內的 logs 資料夾
REM       start.bat D:\logs ...     搜尋指定的資料夾（可多個，或把資料夾拖到這個檔案上）
chcp 65001 >nul
cd /d "%~dp0"
set ARGS=
:loop
if "%~1"=="" goto run
set ARGS=%ARGS% "--root=%~1"
shift
goto loop
:run
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 src\LogViewer.java %ARGS%
pause
