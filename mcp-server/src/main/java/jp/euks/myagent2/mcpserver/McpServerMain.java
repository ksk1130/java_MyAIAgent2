package jp.euks.myagent2.mcpserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * MCP サーバーのエントリーポイント。
 *
 * <p>
 * stdin から JSON-RPC 2.0 リクエストを1行ずつ読み込み、
 * 対応するレスポンスを stdout に1行で出力する。
 * ログはすべて stderr に書き出す（stdout は MCP 通信専用）。
 * </p>
 *
 * <h2>対応メソッド</h2>
 * <ul>
 *   <li>{@code initialize} — サーバー情報を返す</li>
 *   <li>{@code notifications/initialized} — 通知（レスポンスなし）</li>
 *   <li>{@code tools/list} — 利用可能なツール一覧を返す</li>
 *   <li>{@code tools/call} — 指定ツールを実行して結果を返す</li>
 *   <li>{@code ping} — 疎通確認</li>
 * </ul>
 *
 * <h2>起動オプション</h2>
 * <pre>
 *   java -jar mcp-server.jar [--workdir &lt;path&gt;]
 * </pre>
 * {@code --workdir} を省略した場合は JVM の {@code user.dir} を使用する。
 */
public class McpServerMain {

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * エントリーポイント。
     *
     * @param args コマンドライン引数（{@code --workdir <path>} を受け付ける）
     */
    public static void main(String[] args) {
        // stdout を UTF-8 に固定（MCP 通信専用）
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        Path workDir = parseWorkDir(args);

        System.err.println("[MCP] Starting java_MyAIAgent2 MCP server. workDir=" + workDir);

        ToolDispatcher dispatcher = new ToolDispatcher(workDir);

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String response = handleLine(line, dispatcher);
                if (response != null) {
                    out.println(response);
                    out.flush();
                }
            }
        } catch (Exception e) {
            System.err.println("[MCP] Fatal error: " + e.getMessage());
        }

        System.err.println("[MCP] Server exiting.");
    }

    /**
     * 1行分の JSON-RPC メッセージを処理してレスポンス JSON 文字列を返す。
     * Notification（id なし）の場合は {@code null} を返す。
     *
     * @param line       受信した JSON 文字列
     * @param dispatcher ツールディスパッチャー
     * @return レスポンス JSON 文字列（通知の場合は {@code null}）
     */
    static String handleLine(String line, ToolDispatcher dispatcher) {
        JsonObject req;
        try {
            JsonElement el = JsonParser.parseString(line);
            if (!el.isJsonObject()) {
                return errorResponse(null, -32700, "Parse error");
            }
            req = el.getAsJsonObject();
        } catch (Exception e) {
            return errorResponse(null, -32700, "Parse error: " + e.getMessage());
        }

        JsonElement idEl = req.get("id");
        Object id = parseId(idEl);

        String method = req.has("method") ? req.get("method").getAsString() : null;
        if (method == null) {
            return id != null ? errorResponse(id, -32600, "Invalid Request: missing method") : null;
        }

        // Notification（id なし）
        if (id == null) {
            System.err.println("[MCP] notification: " + method);
            return null;
        }

        System.err.println("[MCP] request: " + method + " id=" + id);

        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.get("params").getAsJsonObject()
                : new JsonObject();

        return switch (method) {
            case "initialize" -> handleInitialize(id, params);
            case "tools/list" -> handleToolsList(id, dispatcher);
            case "tools/call" -> handleToolsCall(id, params, dispatcher);
            case "ping" -> successResponse(id, new JsonObject());
            default -> errorResponse(id, -32601, "Method not found: " + method);
        };
    }

    // ------------------------------------------------------------------
    // MCP method handlers
    // ------------------------------------------------------------------

    private static String handleInitialize(Object id, JsonObject params) {
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "java_MyAIAgent2");
        serverInfo.addProperty("version", "1.0.0");

        JsonObject capabilities = new JsonObject();
        JsonObject toolsCapability = new JsonObject();
        capabilities.add("tools", toolsCapability);

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);

        return successResponse(id, result);
    }

    private static String handleToolsList(Object id, ToolDispatcher dispatcher) {
        JsonObject result = new JsonObject();
        result.add("tools", dispatcher.getToolsSchema());
        return successResponse(id, result);
    }

    private static String handleToolsCall(Object id, JsonObject params, ToolDispatcher dispatcher) {
        if (!params.has("name")) {
            return errorResponse(id, -32602, "Invalid params: missing tool name");
        }
        String toolName = params.get("name").getAsString();
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.get("arguments").getAsJsonObject()
                : new JsonObject();

        try {
            ToolDispatcher.ToolResult toolResult = dispatcher.execute(toolName, arguments);
            JsonObject content = new JsonObject();
            content.addProperty("type", "text");
            content.addProperty("text", toolResult.text());

            com.google.gson.JsonArray contentArray = new com.google.gson.JsonArray();
            contentArray.add(content);

            JsonObject result = new JsonObject();
            result.add("content", contentArray);
            result.addProperty("isError", toolResult.isError());

            return successResponse(id, result);
        } catch (Exception e) {
            System.err.println("[MCP] Tool execution error: " + e.getMessage());
            return errorResponse(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // JSON-RPC helpers
    // ------------------------------------------------------------------

    private static String successResponse(Object id, JsonElement result) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        addId(resp, id);
        resp.add("result", result);
        return GSON.toJson(resp);
    }

    private static String errorResponse(Object id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        addId(resp, id);
        resp.add("error", error);
        return GSON.toJson(resp);
    }

    private static void addId(JsonObject obj, Object id) {
        if (id == null) {
            obj.add("id", com.google.gson.JsonNull.INSTANCE);
        } else if (id instanceof Long l) {
            obj.addProperty("id", l);
        } else {
            obj.addProperty("id", id.toString());
        }
    }

    /**
     * JSON-RPC id フィールドを Java オブジェクトに変換する。
     * id が存在しない場合は {@code null} を返す（Notification 扱い）。
     */
    private static Object parseId(JsonElement idEl) {
        if (idEl == null || idEl.isJsonNull()) {
            return null;
        }
        if (idEl.isJsonPrimitive()) {
            var prim = idEl.getAsJsonPrimitive();
            if (prim.isNumber()) {
                return prim.getAsLong();
            }
            return prim.getAsString();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // CLI argument parsing
    // ------------------------------------------------------------------

    private static Path parseWorkDir(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--workdir".equals(args[i])) {
                return Path.of(args[i + 1]).toAbsolutePath().normalize();
            }
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
