@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>&1
if errorlevel 1 (
  echo Moondrop needs Java 17 or newer. Install Java 21 using the Windows steps in INSTALL.md.
  pause
  exit /b 1
)
java -jar "%~dp0moondrop.jar" %*
if errorlevel 1 (
  echo.
  echo Moondrop could not start. Check java -version ^(17+ required^) and see INSTALL.md.
  pause
  exit /b 1
)
