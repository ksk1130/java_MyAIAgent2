@echo off
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set JRE_HOME=%SCRIPT_DIR%jre
set LIB_DIR=%SCRIPT_DIR%lib
set CLASSPATH=

for %%f in ("%LIB_DIR%\*.jar") do (
  set CLASSPATH=!CLASSPATH!;%%~f
)

if "!CLASSPATH:~0,1!"==";" set CLASSPATH=!CLASSPATH:~1!

set JAVA_CMD=
if exist "%JRE_HOME%\bin\java.exe" (
  set "JAVA_CMD=%JRE_HOME%\bin\java.exe"
) else (
  where java >nul 2>nul
  if %ERRORLEVEL%==0 set "JAVA_CMD=java"
)

if not defined JAVA_CMD (
  echo Java launcher java.exe not found. Please install a JRE with java or set JAVA_HOME.
  exit /b 1
)

"%JAVA_CMD%" -cp "!CLASSPATH!" jp.euks.myagent2.chat.App %*
endlocal
