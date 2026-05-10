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
if exist "%JRE_HOME%\bin\javaw.exe" (
  set "JAVA_CMD=%JRE_HOME%\bin\javaw.exe"
) else (
  where javaw >nul 2>nul
  if %ERRORLEVEL%==0 set "JAVA_CMD=javaw"
)

if not defined JAVA_CMD (
  echo Java launcher javaw.exe not found. Please install a JRE with javaw or set JAVA_HOME.
  exit /b 1
)

"%JAVA_CMD%" -cp "!CLASSPATH!" org.example.App %*
endlocal
