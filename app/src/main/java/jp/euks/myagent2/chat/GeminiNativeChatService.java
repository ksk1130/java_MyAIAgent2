package jp.euks.myagent2.chat;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import jp.euks.myagent2.mcp.McpToolRegistry;
import jp.euks.myagent2.tools.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.Objects;

/**
 * Google Gemini Native API 用の ChatService 実装。
 * LangChain4j の GoogleAiGeminiChatModel を使用して、Gemini API に直接接続する。
 * エンドポイント: https://generativelanguage.googleapis.com/v1beta
 */
public class GeminiNativeChatService implements ChatService {
    private static final String DEFAULT_SYSTEM_PROMPT = """
あなたは開発アシスタントです。Git操作やコード検索、ファイル操作のCLIツールの用法を熟知しています。

【原則】
- ツール実行結果は改ざん・解釈・省略せず、そのまま報告する
- 推測や補完は行わず、ツール結果で確認できたもののみ提示する
- 不確実な場合は「確認できない」「該当なし」をそのまま伝える

利用可能なツール:
- `time` — 現在時刻取得（参照のみ）
- `grep` — ワークスペース内検索（参照のみ）
- `gitlog` / `gitshow` / `gitbranch` — Git の読み取り系操作（参照のみ）
- `readfile` / `writefile` — ファイルの読み取り／書き込み
- `readexcel` — Excel ブックから指定シート・セル範囲の値を読み取る
- `readbinary` — Office/PDF は本文抽出テキストを返す
- `localcmd` — ローカルコマンド実行（許可制）
""";

    /**
     * アシスタント用インターフェース。
     */
    public interface Assistant {
        String chat(String message);
    }

    /**
     * ストリーミング用アシスタントインターフェース。
     */
    public interface StreamingAssistant {
        TokenStream chat(String message);
    }

    private final Assistant assistant;
    private final StreamingAssistant streamingAssistant;
    private final ChatMemory chatMemory;
    private final AgentTools agentTools;
    private final ToolExecutionTracker toolExecutionTracker = new ToolExecutionTracker();
    private volatile String systemPrompt = DEFAULT_SYSTEM_PROMPT;
    private volatile Path workingDirectory;
    private volatile MessageWindowChatMemory currentChatMemory;
    private final McpToolRegistry mcpToolRegistry;

    /**
     * 最小限のコンストラクタ（ツールなし）。
     */
    public GeminiNativeChatService(String apiKey, String modelName) {
        this(apiKey, modelName, null, null, null, null, null, null);
    }

    /**
     * ツール付きコンストラクタ。
     */
    public GeminiNativeChatService(
            String apiKey,
            String modelName,
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool,
            LocalCommandTool localCommandTool) {
        this(apiKey, modelName, grepTool, gitTool, fileReaderTool, fileWriterTool, localCommandTool, null);
    }

    /**
     * 完全なコンストラクタ（baseUrl カスタマイズ対応）。
     */
    public GeminiNativeChatService(
            String apiKey,
            String modelName,
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool,
            LocalCommandTool localCommandTool,
            String baseUrl) {
        this(apiKey, modelName, grepTool, gitTool, fileReaderTool, fileWriterTool, localCommandTool, baseUrl, null);
    }

    /**
     * MCP レジストリ付き完全コンストラクタ。
     * {@code mcpRegistry} が非 {@code null} かつ MCP サーバーが設定されている場合は、
     * {@link AiServices} に {@link dev.langchain4j.mcp.McpToolProvider} を追加登録する。
     */
    public GeminiNativeChatService(
            String apiKey,
            String modelName,
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool,
            LocalCommandTool localCommandTool,
            String baseUrl,
            McpToolRegistry mcpRegistry) {

        // Gemini Native API を直接使用（OpenAI互換レイヤーなし）
        var chatModelBuilder = GoogleAiGeminiChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(0.2);
        
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            chatModelBuilder.baseUrl(baseUrl);
        }
        
        ChatModel chatModel = chatModelBuilder.build();

        var streamingModelBuilder = GoogleAiGeminiStreamingChatModel.builder()
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(0.2);
        
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            streamingModelBuilder.baseUrl(baseUrl);
        }

        StreamingChatModel streamingChatModel = streamingModelBuilder.build();

        this.currentChatMemory = MessageWindowChatMemory.withMaxMessages(20);
        this.chatMemory = this.currentChatMemory;
        this.workingDirectory = Path.of(System.getProperty("user.dir"));

        this.agentTools = new AgentTools(
            grepTool,
            gitTool,
            fileReaderTool,
            fileWriterTool,
            localCommandTool,
            new ExcelReaderTool(),
            new BinaryAttachmentStore(workingDirectory),
            toolExecutionTracker);

        this.mcpToolRegistry = mcpRegistry;

        var assistantBuilder = AiServices.builder(Assistant.class)
            .chatModel(chatModel)
            .chatMemory(chatMemory)
            .tools(agentTools);
        if (mcpRegistry != null && mcpRegistry.getToolProvider() != null) {
            assistantBuilder.toolProvider(mcpRegistry.getToolProvider());
        }
        this.assistant = assistantBuilder.build();

        var streamingBuilder = AiServices.builder(StreamingAssistant.class)
            .streamingChatModel(streamingChatModel)
            .chatMemory(chatMemory)
            .tools(agentTools);
        if (mcpRegistry != null && mcpRegistry.getToolProvider() != null) {
            streamingBuilder.toolProvider(mcpRegistry.getToolProvider());
        }
        this.streamingAssistant = streamingBuilder.build();
    }

    @Override
    public String replyTo(String userMessage) {
        return replyToWithHistory(List.of(), userMessage);
    }

    @Override
    public String replyToWithHistory(List<ChatMessage> history, String userMessage) {
        try {
            return assistant.chat(userMessage);
        } catch (Exception e) {
            return "(error) " + e.getMessage();
        }
    }

    @Override
    public void streamReplyToWithHistory(
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onToken,
            Consumer<String> onComplete,
            Consumer<Throwable> onError,
            Consumer<String> onProgress,
            Consumer<TokenInfo> onTokenUsage) {
        try {
            clearMemory();
            if (history != null) {
                for (ChatMessage msg : history) {
                    if (msg != null && msg.role() != null && msg.content() != null) {
                        // Convert jp.euks.myagent2.chat.ChatMessage to langchain4j ChatMessage
                        dev.langchain4j.data.message.ChatMessage lcMsg = convertToLangChain4jMessage(msg);
                        if (lcMsg != null) {
                            chatMemory.add(lcMsg);
                        }
                    }
                }
            }

            StringBuilder accumulated = new StringBuilder();

            streamingAssistant.chat(userMessage)
                .onPartialResponse(token -> {
                    accumulated.append(token);
                    onToken.accept(token);
                })
                .onCompleteResponse(response -> {
                    onComplete.accept(accumulated.toString());
                    if (onTokenUsage != null) {
                        onTokenUsage.accept(new TokenInfo(0, 0));
                    }
                })
                .onError(err -> {
                    onError.accept(err);
                })
                .start();
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /**
     * ChatMessage 型を LangChain4j の ChatMessage に変換する補助メソッド。
     */
    private dev.langchain4j.data.message.ChatMessage convertToLangChain4jMessage(ChatMessage msg) {
        String role = msg.role();
        String content = msg.content();
        
        if ("user".equals(role)) {
            return new UserMessage(content);
        } else if ("assistant".equals(role) || "assistant".equals(role)) {
            return new AiMessage(content);
        } else if ("system".equals(role)) {
            return new SystemMessage(content);
        }
        return null;
    }

    @Override
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = Objects.isNull(prompt) ? DEFAULT_SYSTEM_PROMPT : prompt;
    }

    @Override
    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Override
    public void setWorkingDirectory(Path dir) {
        if (!Objects.isNull(dir)) {
            this.workingDirectory = dir;
        }
        if (mcpToolRegistry != null && dir != null) {
            mcpToolRegistry.reload(dir.toAbsolutePath().normalize());
        }
    }

    @Override
    public McpToolRegistry getMcpToolRegistry() {
        return mcpToolRegistry;
    }

    @Override
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    @Override
    public void clearMemory() {
        if (currentChatMemory != null) {
            currentChatMemory.clear();
        }
    }

    @Override
    public void restoreMemory(List<ChatMessage> messages) {
        clearMemory();
        if (messages != null && currentChatMemory != null) {
            for (ChatMessage msg : messages) {
                if (msg != null && msg.role() != null && msg.content() != null) {
                    dev.langchain4j.data.message.ChatMessage lcMsg = convertToLangChain4jMessage(msg);
                    if (lcMsg != null) {
                        currentChatMemory.add(lcMsg);
                    }
                }
            }
        }
    }

    @Override
    public ToolExecutionTracker getToolExecutionTracker() {
        return toolExecutionTracker;
    }
}
