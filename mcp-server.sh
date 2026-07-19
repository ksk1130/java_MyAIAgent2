#!/bin/sh
# java_MyAIAgent2 MCP Server 起動スクリプト (Unix/macOS)
#
# 使い方:
#   ./mcp-server.sh [--workdir <作業ディレクトリ>]
#
# Claude Desktop や MCP クライアントから呼び出す場合は
# mcp-servers.json の command に以下のように設定してください:
#   "command": ["<このファイルのパス>", "--workdir", "<ワークスペースパス>"]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/mcp-server/build/libs/mcp-server.jar"

if [ ! -f "$JAR" ]; then
    echo "[ERROR] mcp-server.jar が見つかりません: $JAR" >&2
    echo "[ERROR] 先に './gradlew :mcp-server:jar' を実行してください。" >&2
    exit 1
fi

exec java -Dfile.encoding=UTF-8 -jar "$JAR" "$@"
