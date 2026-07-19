@echo off
rem java_MyAIAgent2 MCP Server 起動スクリプト (Windows)
rem
rem 使い方:
rem   mcp-server.bat [--workdir <作業ディレクトリ>]
rem
rem Claude Desktop や MCP クライアントから呼び出す場合は
rem mcp-servers.json の command に以下のように設定してください:
rem   "command": ["cmd", "/c", "<このファイルのパス>", "--workdir", "<ワークスペースパス>"]

setlocal

set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%mcp-server\build\libs\mcp-server.jar"

if not exist "%JAR%" (
    echo [ERROR] mcp-server.jar が見つかりません: %JAR% 1>&2
    echo [ERROR] 先に 'gradlew :mcp-server:jar' を実行してください。 1>&2
    exit /b 1
)

java -Dfile.encoding=UTF-8 -jar "%JAR%" %*
