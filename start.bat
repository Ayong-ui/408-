@echo off
rem Xiuxiantai launcher. Keep this file ASCII-only: cmd misparses multi-byte text after "chcp 65001".
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title Xiuxiantai

where java >nul 2>nul
if errorlevel 1 (
  echo [Xiuxiantai] java not found. Please install JDK 17 or newer.
  pause
  exit /b 1
)

if not exist "target\xiuxiantai.jar" (
  echo [Xiuxiantai] First run: building with Maven, this takes a minute or two...
  call mvn -q -DskipTests package
  if errorlevel 1 (
    echo [Xiuxiantai] Build failed, see the errors above.
    pause
    exit /b 1
  )
)

echo [Xiuxiantai] Starting at http://127.0.0.1:7788/  - close this window to stop.
rem Pass "quiet" (e.g. from the Startup folder) to skip opening the browser.
if /i not "%~1"=="quiet" start "" /b cmd /c "timeout /t 8 /nobreak >nul & start "" http://127.0.0.1:7788/"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar target\xiuxiantai.jar
pause
