package jp.euks.myagent2.chat;



import java.util.Objects;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import jp.euks.myagent2.tools.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * LangChain4j を用いた OpenAI 互換 Chat Completions サービス実装。
 * AiServices により自動的にツール呼び出しが実行される。
 */
public class OpenAiCompatibleChatService implements ChatService {
    private static final String DEFAULT_SYSTEM_PROMPT = """
あなたは開発アシスタントです。Git操作やコード検索、ファイル操作のCLIツールの用法を熟知しています。

【原則】
- ツール実行結果は改ざん・解釈・省略せず、そのまま報告する
- 推測や補完は行わず、ツール結果で確認できたもののみ提示する
- 不確実な場合は「確認できない」「該当なし」をそのまま伝える

利用可能なツール:
- `time` — 現在時刻取得（参照のみ）
- `grep` / `WorkspaceGrepTool` — ワークスペース内検索（参照のみ） rgコマンドが利用可能な場合はrgを優先して使用すること
- `gitlog` / `gitshow` / `gitbranch` — Git の読み取り系操作（参照のみ）
- `readfile` / `writefile` — ファイルの読み取り／書き込み（書き込みは慎重に）
- `readexcel` — Excel ブックから指定シート・セル範囲の値を読み取る（表の一部抽出用途）
- `readbinary` — Office/PDF は本文抽出テキストを返す。抽出テキスト優先、抽出不能な形式のみ base64 を返す
- `localcmd` — ローカルコマンド実行（許可: git/grep/rg/nkf/ls/find/cat/head/tail/wc/stat/diff/sort/uniq/cut/tree/basename/dirname/realpath、基本は参照系だが nkf の `--overwrite` は可、一部禁止文字あり、タイムアウト有り）

補足:
- `nkf` は `localcmd` 経由で利用可能。`/tool cmd nkf --version` のように実行できる。

運用ルール:
1) 可能な限り利用可能なツールを活用すること
2) ユーザーが「ファイル名を探す」「名前にAとBを含みCを含まない」のような**ファイル名条件検索**を依頼した場合、`localcmd` で `rg --files .` を起点にしたパイプ検索を優先すること。
   - 基本形: `rg --files . | rg -ie <include1> -e <include2> | rg -v -e <exclude1> -e <exclude2>`
   - 大文字小文字は `-i` で無視する。
   - include/exclude を複数渡す場合、正規表現の `|` 連結ではなく `-e` を複数使う。
   - 例: 「cmd と local を含み、test を含まない」→ `rg --files . | rg -ie cmd -e local | rg -v -e test`
3) ユーザーが「XXX.xlsxを要約して」「このExcelを要約」など、**ファイル全体の要約/説明**を依頼した場合は `readexcel` ではなく **`readbinary` を最優先**で使うこと。
    - 先に `readbinary <path>` を実行し、返ってきた `extracted_text` を使って要約を続行する。
    - `extracted_text` がある場合はそれを最優先し、`base64` は内容理解に使わない。
    - `[[ATTACH:...]]` トークンは使わないこと。
    - `readexcel` は「Sheet名とセル範囲が明示されている」「特定範囲の集計・確認」など部分抽出の依頼時のみ使うこと。
4) ユーザーが日本語キーワードで検索を依頼した場合、**以下の流れで実行すること（必須）**:
   a) 思考プロセスを必ず表示：「<ユーザーキーワード>から以下のパターンで検索します」と、読み替え理由を含めてキーワード一覧を明示する
      例1:「APIキーを探して」→「APIキー は英語では apiKey, API_KEY, api_key, ApiKey などが考えられるため、以下のパターンで検索します：apiKey, API_KEY, api_key, ApiKey」
      例2:「パス区切りを探して」→「パス区切りはプログラムでは normalizePath, normalize, separator, pathSeparator などの関数や変数で扱われるため、以下のパターンで検索します：normalize, separator, pathSeparator, normalizePath」
   b) 複数パターンで `grep` を実行（rgコマンドが使える場合はrgを優先）。例: `grep -rE "apiKey|API_KEY|api_key|ApiKey" .`
   c) すべての grep 結果を収集して、重複を排除してまとめる
   d) 統一的な形式で提示する
5) `writefile` や `localcmd` のように状態を変更する操作は、必ずユーザーに確認を取り、実行コマンドと影響範囲を明示すること。
6) ツールを呼び出す際は「呼び出し理由」を必ず一行で書き、その後に実行するツール名と引数を記載すること。
7) 出力は簡潔に。必要なら「要点（3行以内）」→「詳細（折りたたみ可能）」の順で提示すること。
8) コードやファイルを示すときは、ワークスペース相対パスと行範囲を明示すること（例: `src/main/java/jp/euks/myagent2/feature/chat/App.java` の `L30-L60`）。
9) 不確実な操作や危険と思われる入力がある場合は実行せず、まずユーザーに確認すること。
10) **ツール結果の真正性** — ツール実行結果は改ざん・解釈・省略せず、以下を徹底すること：
    - ツール実行結果（`localcmd` の結果など）をユーザーにわかりやすく回答に含める。実行コマンドと出力結果の両方を提示する。
    - ファイル名・パスを回答するときは、**ツール出力で確認できたものだけ**を提示する。未確認の推測パスは作らない。
    - 禁止: ツール結果に存在しないファイル名を補完・創作して断定する。
    - 推奨: 「見つからない」「該当なし」をそのまま返し、必要なら検索条件の再提案を行う。

回答フォーマット（優先順）:
- 1行要約: 結論や提案の短い要点
- アクション: 実行した（または提案する）ツール呼び出しの一覧と理由
- ツール結果: 実行結果と出力内容（コマンド実行結果は `$` 記号とともに表示）
- 詳細: 必要なら追加の説明、該当コード断片やファイル参照

常に慎重に、最小限の権限で行動してください。
""";

    private final Assistant assistant;
    private final StreamingAssistant streamingAssistant;
    private final ChatMemory chatMemory;
    private final AgentTools agentTools;
    private volatile String systemPrompt = DEFAULT_SYSTEM_PROMPT;
    private final ToolExecutionTracker toolExecutionTracker = new ToolExecutionTracker();
    private volatile Path workingDirectory;
    private volatile MessageWindowChatMemory currentChatMemory;

    /**
     * アシスタント用の簡単なインターフェース。
     * AiServices が実装を動的に生成する。
     */
    public interface Assistant {
        String chat(String message);
    }

    /**
     * ストリーミング用アシスタントインターフェース。
     * AiServices が TokenStream を返す実装を動的に生成する。
     */
    public interface StreamingAssistant {
        TokenStream chat(String message);
    }

    /**
     * 最小限のコンストラクタ（ツールなし）。
     */
    public OpenAiCompatibleChatService(String baseUrl, String apiKey, String modelName) {
        this(baseUrl, apiKey, modelName, null, null, null, null);
    }

    /**
     * ツール付きコンストラクタ。
     */
    public OpenAiCompatibleChatService(
            String baseUrl,
            String apiKey,
            String modelName,
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool) {
        this(baseUrl, apiKey, modelName, grepTool, gitTool, null, null);
    }

    /**
     * ツール付きコンストラクタ（ファイル読み取り）。
     */
    public OpenAiCompatibleChatService(
            String baseUrl,
            String apiKey,
            String modelName,
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            jp.euks.myagent2.tools.FileReaderTool fileReaderTool) {
        this(baseUrl, apiKey, modelName, grepTool, gitTool, fileReaderTool, null);
    }

    /**
     * 全引数コンストラクタ（LocalCommandTool なし）。
     */
    public OpenAiCompatibleChatService(
            String baseUrl,
            String apiKey,
            String modelName,
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            jp.euks.myagent2.tools.FileReaderTool fileReaderTool,
            jp.euks.myagent2.tools.FileWriterTool fileWriterTool) {
        this(baseUrl, apiKey, modelName, grepTool, gitTool, fileReaderTool, fileWriterTool, null);
    }

    /**
     * 全引数コンストラクタ（LocalCommandTool 付き）。
     */
    public OpenAiCompatibleChatService(
            String baseUrl,
            String apiKey,
            String modelName,
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            jp.euks.myagent2.tools.FileReaderTool fileReaderTool,
            jp.euks.myagent2.tools.FileWriterTool fileWriterTool,
            LocalCommandTool localCommandTool) {
        
        // LangChain4j ChatModel を構築
        ChatModel chatModel = OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(0.2)
            .build();

        // LangChain4j StreamingChatModel を構築
        StreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .temperature(0.2)
            .build();

        // チャット履歴メモリ（ウィンドウ方式）
        this.currentChatMemory = MessageWindowChatMemory.withMaxMessages(20);
        this.chatMemory = this.currentChatMemory;

        // ツール群を構築
        this.agentTools = new AgentTools(
            grepTool,
            gitTool,
            fileReaderTool,
            fileWriterTool,
            localCommandTool,
            new ExcelReaderTool(),
            new BinaryAttachmentStore(Path.of(System.getProperty("user.dir"))),
            toolExecutionTracker);

        // AiServices で Assistant インターフェース実装を生成
        // ツールは自動的に Function Calling として登録される
        this.assistant = AiServices.builder(Assistant.class)
            .chatModel(chatModel)
            .chatMemory(chatMemory)
            .tools(agentTools)
            .build();

        // AiServices でストリーミング用 StreamingAssistant を生成（同じメモリ・ツールを共有）
        this.streamingAssistant = AiServices.builder(StreamingAssistant.class)
            .streamingChatModel(streamingChatModel)
            .chatMemory(chatMemory)
            .tools(agentTools)
            .build();
    }

    @Override
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = (Objects.isNull(prompt)) ? DEFAULT_SYSTEM_PROMPT : prompt;
    }

    @Override
    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Override
    public String replyTo(String userMessage) {
        return replyToWithHistory(List.of(), userMessage);
    }

    @Override
    public String replyToWithHistory(List<ChatMessage> history, String userMessage) {
        try {
            restoreMemory(history);
            // 新しい LLM 呼び出しの前に tracker をクリア
            toolExecutionTracker.clear();
            
            // LangChain4j は履歴を chatMemory で管理し、
            // assistant.chat() が自動的にツール呼び出しを実行する
            return assistant.chat(userMessage);
        } catch (Exception e) {
            return "(error) LLM呼び出しに失敗しました: " + e.getMessage();
        }
    }

    @Override
    public ToolExecutionTracker getToolExecutionTracker() {
        return toolExecutionTracker;
    }

    /**
     * ストリーミング応答＋進捗通知対応。
     * @param history チャット履歴
     * @param userMessage ユーザー発話
     * @param onToken トークン逐次通知
     * @param onComplete 応答完了時
     * @param onError エラー時
     * @param onProgress ツール実行進捗通知（ツール名が切り替わるたび呼ばれる）
     */
    public void streamReplyToWithHistory(
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onToken,
            Consumer<String> onComplete,
            Consumer<Throwable> onError,
            Consumer<String> onProgress) {
        restoreMemory(history);
        toolExecutionTracker.clear();
        StringBuilder accumulated = new StringBuilder();
        final int[] reportedToolCount = new int[] {0};
        streamingAssistant.chat(userMessage)
            .onPartialResponse(token -> {
                accumulated.append(token);
                // キャンセルチェックはオーバーロード版で対応
                onToken.accept(token);
            })
            .onRetrieved(ignored -> {})
            .onToolExecuted(ignored -> {
                // LangChain4j 1.12.2 では onToolExecution が無いため、実行完了通知で進捗表示を更新する。
                if (Objects.isNull(onProgress)) {
                    return;
                }
                var executions = toolExecutionTracker.getExecutions();
                if (executions.size() <= reportedToolCount[0]) {
                    return;
                }
                for (int i = reportedToolCount[0]; i < executions.size(); i++) {
                    String toolName = executions.get(i).toolName();
                    if (toolName != null && !toolName.isBlank()) {
                        onProgress.accept(toolName + "実行中...");
                    }
                }
                reportedToolCount[0] = executions.size();
            })
            .onCompleteResponse(ignored -> {
                if (accumulated.isEmpty()) {
                    try {
                        String result = assistant.chat(userMessage);
                        onToken.accept(result);
                        onComplete.accept(result);
                    } catch (Exception fallbackErr) {
                        onError.accept(fallbackErr);
                    }
                    return;
                }
                onComplete.accept(accumulated.toString());
            })
            .onError(err -> {
                try {
                    accumulated.setLength(0);
                    String result = assistant.chat(userMessage);
                    onToken.accept(result);
                    onComplete.accept(result);
                } catch (Exception fallbackErr) {
                    onError.accept(fallbackErr);
                }
            })
            .start();
    }

    /**
     * キャンセル判定を受け取るオーバーロード版。
     */
    public void streamReplyToWithHistory(
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onToken,
            Consumer<String> onComplete,
            Consumer<Throwable> onError,
            Consumer<String> onProgress,
            java.util.function.BooleanSupplier isCancelled) {
        restoreMemory(history);
        toolExecutionTracker.clear();
        StringBuilder accumulated = new StringBuilder();
        final int[] reportedToolCount = new int[] {0};
        streamingAssistant.chat(userMessage)
            .onPartialResponse(token -> {
                if (isCancelled.getAsBoolean()) return;
                accumulated.append(token);
                onToken.accept(token);
            })
            .onRetrieved(ignored -> {})
            .onToolExecuted(ignored -> {
                if (isCancelled.getAsBoolean()) return;
                if (Objects.isNull(onProgress)) {
                    return;
                }
                var executions = toolExecutionTracker.getExecutions();
                if (executions.size() <= reportedToolCount[0]) {
                    return;
                }
                for (int i = reportedToolCount[0]; i < executions.size(); i++) {
                    String toolName = executions.get(i).toolName();
                    if (toolName != null && !toolName.isBlank()) {
                        onProgress.accept(toolName + "実行中...");
                    }
                }
                reportedToolCount[0] = executions.size();
            })
            .onCompleteResponse(ignored -> {
                if (isCancelled.getAsBoolean()) {
                    // キャンセル時でも完了通知を送って呼び出し元の後片付けを行わせる
                    onComplete.accept("");
                    return;
                }
                if (accumulated.isEmpty()) {
                    try {
                        String result = assistant.chat(userMessage);
                        onToken.accept(result);
                        onComplete.accept(result);
                    } catch (Exception fallbackErr) {
                        onError.accept(fallbackErr);
                    }
                    return;
                }
                onComplete.accept(accumulated.toString());
            })
            .onError(err -> {
                if (isCancelled.getAsBoolean()) {
                    // キャンセル時は通常エラー扱いせず完了通知で片付ける
                    onComplete.accept("");
                    return;
                }
                try {
                    accumulated.setLength(0);
                    String result = assistant.chat(userMessage);
                    onToken.accept(result);
                    onComplete.accept(result);
                } catch (Exception fallbackErr) {
                    onError.accept(fallbackErr);
                }
            })
            .start();
    }

    @Override
    public void setWorkingDirectory(Path dir) {
        if (Objects.isNull(dir)) {
            return;
        }
        Path normalized = dir.toAbsolutePath().normalize();
        this.workingDirectory = normalized;
        agentTools.updateToolReferences(
            new WorkspaceGrepTool(normalized),
            new GitLogTool(normalized),
            new LocalCommandTool(normalized));
        agentTools.updateFileToolReferences(
            new jp.euks.myagent2.tools.FileReaderTool(normalized),
            new jp.euks.myagent2.tools.FileWriterTool(normalized));
        agentTools.updateExcelToolReference(new ExcelReaderTool(normalized));
        agentTools.updateBinaryAttachmentStore(new BinaryAttachmentStore(normalized));
    }

    @Override
    public Path getWorkingDirectory() {
        return this.workingDirectory;
    }

    @Override
    public void clearMemory() {
        if (currentChatMemory != null) {
            currentChatMemory.clear();
        }
    }

    @Override
    public void restoreMemory(List<ChatMessage> history) {
        if (Objects.isNull(currentChatMemory)) {
            return;
        }
        List<dev.langchain4j.data.message.ChatMessage> langchainHistory = new java.util.ArrayList<>();
        // 現在設定されている systemPrompt を毎回先頭に注入し、次の送信から即時反映させる。
        langchainHistory.add(SystemMessage.from(systemPrompt));

        if (history != null) {
            for (ChatMessage message : history) {
                if (Objects.isNull(message) || Objects.isNull(message.role())) {
                    continue;
                }
                String content = Objects.isNull(message.content()) ? "" : message.content();
                jp.euks.myagent2.chat.ChatRole role = jp.euks.myagent2.chat.ChatRole.parse(message.role());
                if (role == null) continue;
                switch (role) {
                    case USER -> langchainHistory.add(UserMessage.from(content));
                    case ASSISTANT -> langchainHistory.add(AiMessage.from(content));
                    case SYSTEM -> {
                        // 既存履歴の system は無視し、現在の設定値を優先する。
                    }
                    default -> {
                    }
                }
            }
        }

        currentChatMemory.set(langchainHistory);
    }
}



