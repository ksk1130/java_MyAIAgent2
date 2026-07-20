package jp.euks.myagent2.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@code mcp-servers.json} 設定ファイルを読み込むユーティリティクラス。
 *
 * <p>
 * 探索順序:
 * <ol>
 *   <li>指定された作業ディレクトリ配下の {@code mcp-servers.json}</li>
 *   <li>クラスパスルートの {@code /mcp-servers.json}（デフォルト設定）</li>
 * </ol>
 * ファイルが存在しない場合は空リストを返す。
 * </p>
 */
public final class McpConfigLoader {

    static final String CONFIG_FILE_NAME = "mcp-servers.json";
    private static final Logger log = Logger.getLogger(McpConfigLoader.class.getName());

    private McpConfigLoader() {
    }

    /**
     * 指定した作業ディレクトリから {@code mcp-servers.json} を読み込み、
     * 有効な（{@code enabled: true}）サーバー設定のリストを返す。
     *
     * @param workDir 設定ファイルを探索するディレクトリ
     * @return 有効なサーバー設定リスト（ファイルが存在しない場合は空リスト）
     */
    public static List<McpServerConfig> load(Path workDir) {
        if (workDir != null) {
            Path configFile = workDir.resolve(CONFIG_FILE_NAME);
            if (Files.exists(configFile)) {
                try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                    List<McpServerConfig> result = parse(reader);
                    log.fine(() -> "Loaded MCP config from " + configFile + ": " + result.size() + " server(s)");
                    return result;
                } catch (IOException e) {
                    log.warning("Failed to load MCP config from " + configFile + ": " + e.getMessage());
                }
            }
        }
        return loadFromClasspath();
    }

    /**
     * クラスパスルートの {@code /mcp-servers.json} を読み込む。
     */
    private static List<McpServerConfig> loadFromClasspath() {
        try (InputStream is = McpConfigLoader.class.getResourceAsStream("/" + CONFIG_FILE_NAME)) {
            if (is == null) {
                return Collections.emptyList();
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                List<McpServerConfig> result = parse(reader);
                log.fine(() -> "Loaded MCP config from classpath: " + result.size() + " server(s)");
                return result;
            }
        } catch (IOException e) {
            log.fine("No MCP config on classpath: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * JSON を解析して {@link McpServerConfig} リストに変換する。
     */
    static List<McpServerConfig> parse(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            return Collections.emptyList();
        }
        JsonObject rootObj = root.getAsJsonObject();
        if (!rootObj.has("servers")) {
            return Collections.emptyList();
        }

        JsonArray serversArray = rootObj.getAsJsonArray("servers");
        List<McpServerConfig> result = new ArrayList<>();

        for (JsonElement el : serversArray) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();

            boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
            if (!enabled) continue;

            String name = getString(obj, "name", "");
            String type = getString(obj, "type", "stdio");
            String url = getString(obj, "url", null);

            List<String> command = new ArrayList<>();
            if (obj.has("command") && obj.get("command").isJsonArray()) {
                for (JsonElement c : obj.getAsJsonArray("command")) {
                    if (c.isJsonPrimitive()) {
                        command.add(c.getAsString());
                    }
                }
            }

            Map<String, String> env = new LinkedHashMap<>();
            if (obj.has("env") && obj.get("env").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("env").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        env.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }

            result.add(new McpServerConfig(name, type, command, env, url, true));
        }

        return Collections.unmodifiableList(result);
    }

    private static String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }
}
