package jp.euks.myagent2.mcp;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP サーバーの1エントリを表す設定レコード。
 *
 * <p>
 * {@code mcp-servers.json} の {@code "servers"} 配列の1要素に対応する。
 * </p>
 *
 * @param name    サーバーの識別名（ログ・エラーメッセージ用）
 * @param type    トランスポート種別: {@code "stdio"}（既定）または {@code "http"}
 * @param command stdio 起動コマンド（例: {@code ["npx", "-y", "@modelcontextprotocol/server-filesystem", "."]}）
 * @param env     stdio 子プロセスに渡す追加環境変数
 * @param url     HTTP/SSE トランスポートの接続先 URL
 * @param enabled {@code false} の場合はこのエントリをスキップする
 */
public record McpServerConfig(
        String name,
        String type,
        List<String> command,
        Map<String, String> env,
        String url,
        boolean enabled
) {
    public McpServerConfig {
        if (command == null) command = Collections.emptyList();
        if (env == null) env = Collections.emptyMap();
        if (type == null || type.isBlank()) type = "stdio";
    }
}
