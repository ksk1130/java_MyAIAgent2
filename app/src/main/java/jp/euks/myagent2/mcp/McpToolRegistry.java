package jp.euks.myagent2.mcp;

import com.google.gson.Gson;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP サーバーへの接続を管理し、LLM ツール呼び出しと手動 {@code /tool} コマンドの
 * 両方に対してプラガブルなツール実行基盤を提供するレジストリ。
 *
 * <h2>ライフサイクル</h2>
 * <ul>
 *   <li>初回アクセス時に {@code mcp-servers.json} を読み込んで MCP クライアントを初期化する（遅延初期化）。</li>
 *   <li>{@link #reload(Path)} で作業ディレクトリを変更した場合、既存の接続を閉じて再接続する。</li>
 *   <li>{@link #close()} でリソースを解放する。</li>
 * </ul>
 *
 * <h2>使用方法（LLM ツール統合）</h2>
 * <pre>{@code
 * McpToolRegistry registry = new McpToolRegistry(workDir);
 * AiServices.builder(Assistant.class)
 *     .chatModel(chatModel)
 *     .tools(agentTools)                  // 既存の組み込みツール
 *     .toolProvider(registry.getToolProvider())  // MCP ツール（設定済み時のみ）
 *     .build();
 * }</pre>
 *
 * <h2>使用方法（手動コマンド）</h2>
 * <pre>{@code
 * String result = registry.executeTool("my_mcp_tool", Map.of("query", "hello"));
 * // result == null のとき、そのツールは MCP サーバーに存在しない
 * }</pre>
 */
public class McpToolRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(60);

    private Path workDir;
    private final List<McpClient> clients = new CopyOnWriteArrayList<>();
    private volatile McpToolProvider toolProvider;
    private volatile boolean initialized = false;

    /**
     * 指定した作業ディレクトリで MCP レジストリを作成する。
     * 接続は最初のアクセス時（遅延）に確立される。
     *
     * @param workDir {@code mcp-servers.json} を探索するディレクトリ（{@code null} の場合は {@code user.dir}）
     */
    public McpToolRegistry(Path workDir) {
        this.workDir = workDir != null ? workDir : Path.of(System.getProperty("user.dir"));
    }

    /**
     * 作業ディレクトリを変更して MCP クライアントを再初期化する。
     * 同じパスを指定した場合は何もしない（冪等）。
     *
     * @param newWorkDir 新しい作業ディレクトリ（{@code null} の場合は現在値を保持）
     */
    public synchronized void reload(Path newWorkDir) {
        Path resolved = newWorkDir != null ? newWorkDir.toAbsolutePath().normalize() : this.workDir;
        if (initialized && resolved.equals(this.workDir)) {
            return;
        }
        this.workDir = resolved;
        closeClients();
        initialized = false;
    }

    /**
     * MCP サーバーが1つ以上設定・接続されているかどうかを返す。
     *
     * @return MCP サーバーが有効な場合 {@code true}
     */
    public boolean hasMcpServers() {
        ensureInitialized();
        return !clients.isEmpty();
    }

    /**
     * LangChain4j {@code AiServices} に渡す {@link McpToolProvider} を返す。
     * MCP サーバーが設定されていない場合は {@code null} を返す。
     *
     * @return {@link McpToolProvider}、またはサーバー未設定時は {@code null}
     */
    public McpToolProvider getToolProvider() {
        ensureInitialized();
        return toolProvider;
    }

    /**
     * 全 MCP サーバーから利用可能なツール名の一覧を返す。
     * 接続エラーが発生したサーバーはスキップする。
     *
     * @return ツール名リスト（順序はサーバー・ツール登録順）
     */
    public List<String> listToolNames() {
        ensureInitialized();
        List<String> names = new ArrayList<>();
        for (McpClient client : clients) {
            try {
                for (ToolSpecification spec : client.listTools()) {
                    names.add(spec.name());
                }
            } catch (Exception e) {
                log.warn("Failed to list tools from MCP client: {}", e.getMessage());
            }
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * 指定したツールを MCP サーバー経由で実行する。
     *
     * <p>
     * いずれかのサーバーにツールが存在すれば実行して結果テキストを返す。
     * 引数は Gson でシリアライズされる。
     * どのサーバーにもツールがない場合は {@code null} を返す（組み込みツールへのフォールバックを想定）。
     * </p>
     *
     * @param toolName ツール名
     * @param args     ツール引数（キー: パラメータ名、値: 文字列または任意のオブジェクト）
     * @return ツール実行結果テキスト、またはツールが見つからない場合は {@code null}
     */
    public String executeTool(String toolName, Map<String, Object> args) {
        ensureInitialized();
        Gson gson = new Gson();

        for (McpClient client : clients) {
            try {
                boolean found = client.listTools().stream()
                        .anyMatch(s -> s.name().equals(toolName));
                if (!found) {
                    continue;
                }

                String argsJson = gson.toJson(args != null ? args : Collections.emptyMap());
                ToolExecutionRequest request = ToolExecutionRequest.builder()
                        .name(toolName)
                        .arguments(argsJson)
                        .build();
                ToolExecutionResult result = client.executeTool(request);
                return result.resultText();
            } catch (Exception e) {
                log.warn("MCP tool execution failed for '{}': {}", toolName, e.getMessage());
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    private synchronized void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private void initialize() {
        List<McpServerConfig> configs = McpConfigLoader.load(workDir);
        for (McpServerConfig cfg : configs) {
            try {
                McpClient client = createClient(cfg);
                clients.add(client);
                log.info("MCP client connected: '{}' ({})", cfg.name(), cfg.type());
            } catch (Exception e) {
                log.warn("Failed to create MCP client for '{}': {}", cfg.name(), e.getMessage());
            }
        }
        if (!clients.isEmpty()) {
            toolProvider = McpToolProvider.builder()
                    .mcpClients(clients)
                    .failIfOneServerFails(false)
                    .build();
        }
        initialized = true;
    }

    private McpClient createClient(McpServerConfig cfg) {
        var builder = new DefaultMcpClient.Builder()
                .clientName("java_MyAIAgent2")
                .clientVersion("1.0")
                .initializationTimeout(INIT_TIMEOUT)
                .toolExecutionTimeout(TOOL_TIMEOUT)
                .cacheToolList(true);

        if ("http".equalsIgnoreCase(cfg.type())) {
            var transport = HttpMcpTransport.builder()
                    .sseUrl(cfg.url())
                    .build();
            builder.transport(transport);
        } else {
            // default: stdio
            var transport = StdioMcpTransport.builder()
                    .command(cfg.command())
                    .environment(cfg.env())
                    .build();
            builder.transport(transport);
        }

        return builder.build();
    }

    private void closeClients() {
        for (McpClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Error closing MCP client: {}", e.getMessage());
            }
        }
        clients.clear();
        toolProvider = null;
    }

    /**
     * 全 MCP クライアント接続を閉じてリソースを解放する。
     */
    @Override
    public void close() {
        closeClients();
    }
}
