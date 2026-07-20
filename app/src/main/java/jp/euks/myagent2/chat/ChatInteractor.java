package jp.euks.myagent2.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Objects;
import jp.euks.myagent2.mcpserver.*;
import jp.euks.myagent2.common.*;
import jp.euks.myagent2.session.*;
import jp.euks.myagent2.tools.BinaryAttachmentStore;
import jp.euks.myagent2.tools.DefaultManualToolExecutor;
import jp.euks.myagent2.tools.ManualToolExecutor;
import jp.euks.myagent2.tools.ToolExecutionTracker;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * UI 層と ChatService の仲介を行うインタラクタ。
 * 手動ツールコマンドを優先的に処理し、そうでなければチャットサービスへ履歴付きで委譲する。
 */
public class ChatInteractor {
    private static final String TOOL_RESULTS_BEGIN_MARKER = "<<TOOL_RESULTS_BEGIN>>";
    private static final String TOOL_RESULTS_END_MARKER = "<<TOOL_RESULTS_END>>";
    private static final String CLEAR_REPLY = "会話履歴を削除しました";
    private static final String CLEAR_TURN_TEXT = "You: /clear\nAssistant: " + CLEAR_REPLY + "\n\n";
    private static final Pattern ATTACHMENT_TOKEN_PATTERN = Pattern.compile("\\[\\[ATTACH:([a-f0-9\\-]{36})\\]\\]");
    private static final Pattern ASSISTANT_ATTACHMENT_TOKEN_PATTERN = Pattern.compile("\\[\\[ATTACH:[^\\]]+\\]\\]",
            Pattern.CASE_INSENSITIVE);
    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_GEMINI = "gemini";

    private final ChatService chatService;
    private final ManualToolExecutor manualToolExecutor;
    private final ConversationStore conversationStore;
    private final StringBuilder transcript;
    private final List<ChatMessage> conversationHistory;
    private final ConversationSession currentSession;
    private BinaryAttachmentStore attachmentStore;
    private volatile boolean busy = false;
    private final AtomicLong requestCounter = new AtomicLong(0L);
    private volatile long activeRequestId = 0L;
    private volatile boolean cancelled = false;
    private volatile TokenInfo lastTokenInfo = null;  // 最後のトークン情報を保持

    /**
     * 単純なコンストラクタ。既定の ManualToolExecutor を使用する。
     *
     * @param chatService チャット応答を生成するサービス
     */
    public ChatInteractor(ChatService chatService) {
        this(chatService, new DefaultManualToolExecutor(), null);
    }

    /**
     * カスタムの ManualToolExecutor を注入できるコンストラクタ。
     *
     * @param chatService        チャット応答サービス
     * @param manualToolExecutor 手動ツール実行器
     */
    public ChatInteractor(ChatService chatService, ManualToolExecutor manualToolExecutor) {
        this(chatService, manualToolExecutor, null);
    }

    /**
     * 永続化ストアを含むコンストラクタ。
     * ストアが指定されている場合は起動時に最新履歴を読み込む。
     *
     * @param chatService        チャット応答サービス
     * @param manualToolExecutor 手動ツール実行器
     * @param conversationStore  会話履歴ストア（null の場合はメモリのみ）
     */
    public ChatInteractor(
            ChatService chatService,
            ManualToolExecutor manualToolExecutor,
            ConversationStore conversationStore) {
        this(chatService, manualToolExecutor, conversationStore, null);
    }

    /**
     * 永続化ストアとセッションIDを指定するコンストラクタ。
     *
     * @param chatService        チャット応答サービス
     * @param manualToolExecutor 手動ツール実行器
     * @param conversationStore  会話履歴ストア（null の場合はメモリのみ）
     * @param sessionId          読み込むセッションID（null/空の場合は最新）
     */
    public ChatInteractor(
            ChatService chatService,
            ManualToolExecutor manualToolExecutor,
            ConversationStore conversationStore,
            String sessionId) {
        this(chatService, manualToolExecutor, conversationStore, sessionId, null);
    }

    /**
     * 永続化ストア・セッションID・添付ストアを指定するコンストラクタ（テスト用）。
     *
     * @param chatService        チャット応答サービス
     * @param manualToolExecutor 手動ツール実行器
     * @param conversationStore  会話履歴ストア（null の場合はメモリのみ）
     * @param sessionId          読み込むセッションID（null/空の場合は最新）
     * @param attachmentStore    添付ストア（null の場合は新規作成）
     */
    public ChatInteractor(
            ChatService chatService,
            ManualToolExecutor manualToolExecutor,
            ConversationStore conversationStore,
            String sessionId,
            BinaryAttachmentStore attachmentStore) {
        this.chatService = chatService;
        this.manualToolExecutor = manualToolExecutor;
        this.conversationStore = conversationStore;
        this.transcript = new StringBuilder();
        this.conversationHistory = new ArrayList<>();

        ConversationSession loadedSession = null;
        if (conversationStore != null) {
            if (sessionId == null || sessionId.isBlank()) {
                loadedSession = conversationStore.loadLatestOrCreate();
            } else {
                loadedSession = conversationStore.loadByIdOrCreate(sessionId);
            }
            // セッションに保存された作業ディレクトリを ChatService に反映する。
            // 空の場合は user.dir をフォールバックとして使用し、必ず一定の状態に設定する。
            if (loadedSession != null) {
                String wd = loadedSession.workingDirectory();
                java.nio.file.Path wdPath;
                if (wd != null && !wd.isBlank()) {
                    try {
                        wdPath = java.nio.file.Path.of(wd);
                    } catch (java.nio.file.InvalidPathException ignored) {
                        wdPath = java.nio.file.Path.of(System.getProperty("user.dir"));
                    }
                } else {
                    wdPath = java.nio.file.Path.of(System.getProperty("user.dir"));
                }
                chatService.setWorkingDirectory(wdPath);
                // セッション側にも保存して JSON と一致させておく
                loadedSession.setWorkingDirectory(wdPath.toString());
                chatService.restoreMemory(loadedSession.messages());
            }
            this.conversationHistory.addAll(loadedSession.messages());
            // 保存済みトランスクリプトがあればそれを優先使用する（ツール結果マーカーを保持するため）
            String savedTranscript = loadedSession.transcript();
            if (savedTranscript != null && !savedTranscript.isEmpty()) {
                this.transcript.append(savedTranscript);
            } else {
                this.transcript.append(formatTranscript(this.conversationHistory));
            }
        }
        this.currentSession = loadedSession;
        this.attachmentStore = attachmentStore != null
                ? attachmentStore
                : new BinaryAttachmentStore(getWorkingDirectory());
    }

    /**
     * ユーザーの入力を処理し、画面に表示する形式のターン文字列を返す。
     * - /clear コマンドは最優先で履歴を完全削除
     * - 入力が手動ツールコマンドならその実行結果を返す
     * - そうでなければ `chatService.replyToWithHistory` を呼ぶ
     *
     * @param rawInput ユーザー入力（null/空は無視される）
     * @return 画面に追加するターン文字列
     */
    public String onUserMessage(String rawInput) {
        if (Objects.isNull(rawInput)) {
            return "";
        }

        String userMessage = rawInput.trim();
        if (userMessage.isEmpty()) {
            return "";
        }

        ExecutionLogger.logRequest(userMessage);

        // /clear コマンドは最優先で履歴を完全削除
        if (userMessage.equalsIgnoreCase("/clear")) {
            clearConversationState();
            ExecutionLogger.logReply(CLEAR_REPLY);
            return CLEAR_TURN_TEXT;
        }

        var manualResult = manualToolExecutor.tryExecute(userMessage);
        if (manualResult.isPresent()) {
            String assistantMessage = manualResult.get();
            syncWorkingDirectoryIfSetdirSucceeded(userMessage, assistantMessage);
            String turnText = appendTurnAndPersist(userMessage, assistantMessage);
            ExecutionLogger.logReply(assistantMessage);
            return turnText;
        }

        AttachmentExpansionResult expansion = expandAttachmentTokens(userMessage);
        if (!expansion.success()) {
            String assistantMessage = "(error) " + expansion.errorMessage();
            String turnText = appendTurnAndPersist(userMessage, assistantMessage);
            ExecutionLogger.logReply(assistantMessage);
            return turnText;
        }

        String baseAssistantMessage = chatService.replyToWithHistory(conversationHistory, expansion.expandedMessage());
        String assistantMessage = baseAssistantMessage;
        String toolResultsText = "";

        // /tool setdir 成功時は ChatService 側のツール基点も同期する
        syncWorkingDirectoryIfSetdirSucceeded(userMessage, assistantMessage);

        // LLM が tool calling を実行した場合、tracker から結果を取得して表示に追加
        ToolExecutionTracker tracker = chatService.getToolExecutionTracker();
        java.util.List<String> toolNames = new java.util.ArrayList<>();
        if (tracker != null) {
            var executions = tracker.getExecutions();
            if (!executions.isEmpty()) {
                for (var exec : executions) {
                    toolNames.add(exec.toolName());
                }
                StringBuilder toolResults = new StringBuilder();
                for (var exec : executions) {
                    if (!toolResults.isEmpty()) {
                        toolResults.append("\n\n");
                    }
                    toolResults.append(exec.format());
                }
                // tool 結果を LLM 応答の前に付加
                if (!toolResults.isEmpty()) {
                    toolResultsText = toolResults.toString();
                }
            }
        }

        baseAssistantMessage = guardAssistantResponse(userMessage, baseAssistantMessage);
        assistantMessage = toolResultsText.isEmpty()
                ? baseAssistantMessage
                : toolResultsText + "\n\n" + baseAssistantMessage;

        if (!toolNames.isEmpty()) {
            ExecutionLogger.logToolExecution(toolNames);
        }

        conversationHistory.add(new ChatMessage("user", userMessage));
        conversationHistory.add(new ChatMessage("assistant", assistantMessage));

        String turnText;
        if (!toolResultsText.isEmpty()) {
            // ツール結果と最終回答の境界を明示して、UI 側で正しく分離できるようにする。
            turnText = "You: %s\n".formatted(userMessage)
                    + "Assistant: %s\n".formatted(TOOL_RESULTS_BEGIN_MARKER)
                    + toolResultsText + "\n"
                    + "Assistant: %s\n".formatted(TOOL_RESULTS_END_MARKER)
                    + "Assistant: %s\n\n".formatted(baseAssistantMessage);
        } else {
            turnText = "You: %s\n".formatted(userMessage)
                    + "Assistant: %s\n\n".formatted(assistantMessage);
        }
        transcript.append(turnText);
        persistConversation();
        ExecutionLogger.logReply(baseAssistantMessage);
        return turnText;
    }

    /**
     * 現在のトランスクリプト文字列を返します。
     * 
     * @return トランスクリプト文字列
     */
    public String getTranscript() {
        return transcript.toString();
    }

    /**
     * 最後のトークン使用情報を返す。
     * startUserMessageStream 完了後に呼び出して、UI に表示する際に使用。
     *
     * @return 最後の TokenInfo、またはまだ処理がない場合は null
     */
    public TokenInfo getLastTokenInfo() {
        return lastTokenInfo;
    }

    /**
     * 現在のセッションで累積された入力トークン数を返す。
     *
     * @return 累積入力トークン数
     */
    public long getSessionTotalInputTokens() {
        return currentSession == null ? 0L : currentSession.totalInputTokens();
    }

    /**
     * 現在のセッションで累積された出力トークン数を返す。
     *
     * @return 累積出力トークン数
     */
    public long getSessionTotalOutputTokens() {
        return currentSession == null ? 0L : currentSession.totalOutputTokens();
    }

    /**
     * 現在のセッションで累積された合計トークン数を返す。
     *
     * @return 累積合計トークン数
     */
    public long getSessionTotalTokens() {
        return getSessionTotalInputTokens() + getSessionTotalOutputTokens();
    }

    /**
     * ストリーミングでユーザーメッセージを処理する（進捗コールバック対応）。
     * 
     * @param rawInput   ユーザー入力
     * @param onToken    トークン到着時コールバック
     * @param onProgress ツール実行進捗コールバック（ツール名が切り替わるたび呼ばれる）
     * @param onComplete 完了時コールバック
     * @param onError    エラー時コールバック
     */
    public void startUserMessageStream(
            String rawInput,
            Consumer<String> onToken,
            Consumer<String> onProgress,
            Runnable onComplete,
            Consumer<Throwable> onError) {

        if (Objects.isNull(rawInput)) {
            onComplete.run();
            return;
        }
        String userMessage = rawInput.trim();
        if (userMessage.isEmpty()) {
            onComplete.run();
            return;
        }

        ExecutionLogger.logRequest(userMessage);

        // /clear コマンドは最優先で履歴を完全削除
        if (userMessage.equalsIgnoreCase("/clear")) {
            clearConversationState();
            onToken.accept(CLEAR_TURN_TEXT);
            ExecutionLogger.logReply(CLEAR_REPLY);
            onComplete.run();
            return;
        }

        // 手動ツールコマンドは同期的に処理してストリーミング不要
        var manualResult = manualToolExecutor.tryExecute(userMessage);
        if (manualResult.isPresent()) {
            String assistantMessage = manualResult.get();
            syncWorkingDirectoryIfSetdirSucceeded(userMessage, assistantMessage);
            String turnText = appendTurnAndPersist(userMessage, assistantMessage);
            onToken.accept(turnText);
            ExecutionLogger.logReply(assistantMessage);
            onComplete.run();
            return;
        }

        AttachmentExpansionResult expansion = expandAttachmentTokens(userMessage);
        if (!expansion.success()) {
            String assistantMessage = "(error) " + expansion.errorMessage();
            String turnText = appendTurnAndPersist(userMessage, assistantMessage);
            onToken.accept(turnText);
            ExecutionLogger.logReply(assistantMessage);
            onComplete.run();
            return;
        }

        // LLM ストリーミング: "You: ..." ヘッダーをまず通知
        String youHeader = "You: %s\nAssistant: ".formatted(userMessage);
        onToken.accept(youHeader);

        // リクエスト ID を発行してキャンセル状態を管理
        long requestId = requestCounter.incrementAndGet();
        activeRequestId = requestId;
        cancelled = false;
        // 新しいリクエスト開始時に前回のトークン情報をリセットする。
        lastTokenInfo = null;

        // マーク: ストリーミング中フラグを立て、完了時/エラー時にクリアする
        busy = true;
        Runnable wrappedOnComplete = () -> {
            busy = false;
            onComplete.run();
        };
        Consumer<Throwable> wrappedOnError = (err) -> {
            busy = false;
            onError.accept(err);
        };

        BooleanSupplier isCancelledSupplier = () -> (activeRequestId != requestId) || cancelled;

        try {
            AtomicLong totalInputTokens = new AtomicLong(0);
            AtomicLong totalOutputTokens = new AtomicLong(0);
            chatService.streamReplyToWithHistory(
                    conversationHistory,
                    expansion.expandedMessage(),
                    token -> {
                        if (isCancelledSupplier.getAsBoolean())
                            return;
                        onToken.accept(token);
                    }, // onToken
                    fullLlmText -> {
                        // onComplete: ツール実行結果を処理して会話履歴に追加
                        String assistantMessage = fullLlmText;
                        String toolResultsText = "";
                        String finalLlmText = fullLlmText;
                        ToolExecutionTracker tracker = chatService.getToolExecutionTracker();
                        java.util.List<String> toolNames = new java.util.ArrayList<>();
                        if (tracker != null) {
                            var executions = tracker.getExecutions();
                            finalLlmText = guardAssistantResponse(userMessage, finalLlmText);
                            if (!finalLlmText.equals(fullLlmText)) {
                                onToken.accept("\n" + finalLlmText);
                            }
                            assistantMessage = finalLlmText;
                            if (!executions.isEmpty()) {
                                for (var exec : executions) {
                                    toolNames.add(exec.toolName());
                                }
                                StringBuilder toolResults = new StringBuilder();
                                for (var exec : executions) {
                                    if (!toolResults.isEmpty()) {
                                        toolResults.append("\n\n");
                                    }
                                    toolResults.append(exec.format());
                                }
                                if (!toolResults.isEmpty()) {
                                    toolResultsText = toolResults.toString();
                                    assistantMessage = toolResults.toString() + "\n\n" + finalLlmText;
                                }
                            }
                        }

                        if (!toolNames.isEmpty()) {
                            ExecutionLogger.logToolExecution(toolNames);
                        }

                        syncWorkingDirectoryIfSetdirSucceeded(userMessage, assistantMessage);
                        
                        // トークン情報を保持（UI表示用）
                        // メッセージに埋め込まない
                        if (totalInputTokens.get() > 0 || totalOutputTokens.get() > 0) {
                            lastTokenInfo = new TokenInfo((int) totalInputTokens.get(), (int) totalOutputTokens.get());
                            // セッション累積にも加算しておく（persistConversation が保存する）
                            if (currentSession != null) {
                                currentSession.addTokenUsage(totalInputTokens.get(), totalOutputTokens.get());
                            }
                        }
                        
                        conversationHistory.add(new ChatMessage("user", userMessage));
                        conversationHistory.add(new ChatMessage("assistant", assistantMessage));

                        // transcript はストリーミング中は更新せず、完了時に全ターンテキストを追加
                        String turnText;
                        if (!toolResultsText.isEmpty()) {
                            turnText = "You: %s\n".formatted(userMessage)
                                    + "Assistant: %s\n".formatted(TOOL_RESULTS_BEGIN_MARKER)
                                    + toolResultsText + "\n"
                                    + "Assistant: %s\n".formatted(TOOL_RESULTS_END_MARKER)
                                    + "Assistant: %s\n\n".formatted(finalLlmText);
                        } else {
                            turnText = "You: %s\n".formatted(userMessage)
                                    + "Assistant: %s\n\n".formatted(assistantMessage);
                        }
                        transcript.append(turnText);
                        persistConversation();
                        ExecutionLogger.logReply(finalLlmText);
                        wrappedOnComplete.run();
                    },
                    wrappedOnError,
                    progressText -> {
                        if (isCancelledSupplier.getAsBoolean())
                            return;
                        if (onProgress != null)
                            onProgress.accept(progressText);
                    },
                    tokenInfo -> {
                        if (tokenInfo == null) {
                            return;
                        }
                        totalInputTokens.addAndGet(tokenInfo.inputTokens());
                        totalOutputTokens.addAndGet(tokenInfo.outputTokens());
                    },
                    isCancelledSupplier);
        } catch (Exception e) {
            busy = false;
            onError.accept(e);
        }
    }

    /**
     * ユーザーメッセージをストリーミングで処理する簡易版。進捗コールバックなし。
     * 
     * @param rawInput   ユーザー入力
     * @param onToken    トークン到着時コールバック
     * @param onComplete 完了時コールバック
     * @param onError    エラー時コールバック
     */
    public void startUserMessageStream(
            String rawInput,
            Consumer<String> onToken,
            Runnable onComplete,
            Consumer<Throwable> onError) {
        startUserMessageStream(rawInput, onToken, null, onComplete, onError);
    }

    /**
     * 現在のセッションに保存された作業ディレクトリを返す。
     * JSON が源泉であり、空の場合は user.dir を返す。
     *
     * @return セッションの作業ディレクトリパス（null にはならない）
     */
    public java.nio.file.Path getWorkingDirectory() {
        if (currentSession != null) {
            String wd = currentSession.workingDirectory();
            if (wd != null && !wd.isBlank()) {
                try {
                    return java.nio.file.Path.of(wd);
                } catch (java.nio.file.InvalidPathException ignored) {
                }
            }
        }
        // フォールバック: chatService の現在値、それも null なら user.dir
        java.nio.file.Path svc = chatService.getWorkingDirectory();
        return svc != null ? svc : java.nio.file.Path.of(System.getProperty("user.dir"));
    }

    /**
     * 作業ディレクトリを更新し、即座にセッションへ永続化する。
     * 「変更」ボタン等でメッセージ送信前に変更した場合も確実に保存される。
     *
     * @param dir 新しい作業ディレクトリ
     */
    public void updateWorkingDirectory(java.nio.file.Path dir) {
        if (Objects.isNull(dir))
            return;
        chatService.setWorkingDirectory(dir);
        attachmentStore = new BinaryAttachmentStore(dir);
        if (currentSession != null) {
            currentSession.setWorkingDirectory(dir.toString());
            if (conversationStore != null) {
                conversationStore.save(currentSession);
            }
        }
    }

    /**
     * システムプロンプトを設定する。設定は即座に ChatService へ委譲される。
     *
     * @param prompt 新しいシステムプロンプト
     */
    public void setSystemPrompt(String prompt) {
        chatService.setSystemPrompt(prompt);
    }

    /**
     * 現在のシステムプロンプトを返す。
     *
     * @return システムプロンプト文字列
     */
    public String getSystemPrompt() {
        return chatService.getSystemPrompt();
    }

    public String getCurrentSessionId() {
        return Objects.isNull(currentSession) ? "" : currentSession.sessionId();
    }

    /**
     * 指定セッションのタイトルを更新して永続化します。
     *
     * @param session  対象セッション
     * @param newTitle 新しいタイトル
     */
    public void changeTitle(ConversationSession session, String newTitle) {
        if (Objects.isNull(session)) {
            return;
        }
        session.setTitle(newTitle);
        if (conversationStore != null) {
            conversationStore.save(session);
        }
    }

    /**
     * 現在のセッションのタイトルを更新して永続化します。
     *
     * @param newTitle 新しいタイトル
     */
    public void changeCurrentSessionTitle(String newTitle) {
        changeTitle(currentSession, newTitle);
    }

    /**
     * ストリーミング中かどうかを返す（evictの判断に使用）。
     */
    public boolean isBusy() {
        return busy;
    }

    /**
     * 現在のセッション状態を永続化する公開ラッパー。
     */
    public void save() {
        persistConversation();
    }

    /**
     * 現在のセッション状態を永続化するが、セッションの `updatedAt` を更新しない保存を行う。
     * 主にランタイム退避時のディスク書き出しに使用する。
     */
    public void saveWithoutTouch() {
        if (Objects.isNull(conversationStore) || Objects.isNull(currentSession)) {
            return;
        }

        // 同期: 作業ディレクトリ・メッセージ・トランスクリプトは通常通り同期するが updatedAt は触らない
        java.nio.file.Path currentWd = chatService.getWorkingDirectory();
        if (currentWd != null) {
            currentSession.setWorkingDirectory(currentWd.toString());
        }
        currentSession.replaceMessages(conversationHistory);
        currentSession.setTranscript(transcript.toString());
        conversationStore.saveWithoutTouch(currentSession);
    }

    /**
     * 現在の進行中リクエストをキャンセルする。UI側のキャンセル操作から呼ぶ想定。
     */
    public void cancelCurrentRequest() {
        // 単純化: フラグを立てるだけでストリーム実行側がチェックして停止する
        this.cancelled = true;
        this.activeRequestId = 0L;
    }

    /**
     * 会話履歴からトランスクリプト文字列を生成するユーティリティ。
     */
    private void persistConversation() {
        if (Objects.isNull(conversationStore) || Objects.isNull(currentSession)) {
            return;
        }

        // 現在の ChatService の作業ディレクトリをセッションに同期して保存
        java.nio.file.Path currentWd = chatService.getWorkingDirectory();
        if (currentWd != null) {
            currentSession.setWorkingDirectory(currentWd.toString());
        }
        currentSession.replaceMessages(conversationHistory);
        currentSession.setTranscript(transcript.toString());
        conversationStore.save(currentSession);
    }

    /**
     * 会話履歴からトランスクリプト文字列を生成するユーティリティ。
     */
    private void clearConversationState() {
        conversationHistory.clear();
        transcript.setLength(0);
        chatService.clearMemory();
        if (currentSession == null) {
            return;
        }
        currentSession.replaceMessages(List.of());
        // /clear は累積トークンもリセットする仕様
        currentSession.clearTokenUsage();
        if (conversationStore != null) {
            conversationStore.save(currentSession);
        }
    }

    /**
     * ユーザーメッセージとアシスタントメッセージを会話履歴に追加し、トランスクリプトも更新して永続化するユーティリティ。
     * 
     * @param userMessage      ユーザーメッセージ
     * @param assistantMessage アシスタントメッセージ
     * @return 追加されたターンのテキスト表現（You: ... Assistant: ...）
     */
    private String appendTurnAndPersist(String userMessage, String assistantMessage) {
        conversationHistory.add(new ChatMessage("user", userMessage));
        conversationHistory.add(new ChatMessage("assistant", assistantMessage));
        String turnText = "You: %s\n".formatted(userMessage)
                + "Assistant: %s\n\n".formatted(assistantMessage);
        transcript.append(turnText);
        persistConversation();
        return turnText;
    }

    /**
     * /tool setdir コマンドが成功した場合に ChatService 側の作業ディレクトリも同期する。
     * 
     * @param userMessage      ユーザーメッセージ
     * @param assistantMessage アシスタントメッセージ
     */
    private void syncWorkingDirectoryIfSetdirSucceeded(String userMessage, String assistantMessage) {
        if (!userMessage.matches("^/tool\\s+setdir\\b.*")) {
            return;
        }
        if (!assistantMessage.startsWith("(tool:setdir)")) {
            return;
        }

        String pathText = userMessage.replaceFirst("^/tool\\s+setdir\\s*", "").trim();
        if (pathText.isEmpty()) {
            return;
        }

        try {
            Path dir = Path.of(pathText);
            chatService.setWorkingDirectory(dir);
            attachmentStore = new BinaryAttachmentStore(dir);
        } catch (InvalidPathException ignored) {
            // setdir 側で既にエラー扱いのため、ここでは何もしない
        }
    }

    /**
     * ユーザーメッセージ内の添付ファイルトークン ([[ATTACH:attachmentId]]) を展開する。
     * 
     * @param userMessage ユーザーメッセージ
     * @return 展開結果。成功時は展開後のメッセージ、失敗時はエラーメッセージを含む。
     */
    private AttachmentExpansionResult expandAttachmentTokens(String userMessage) {
        Matcher matcher = ATTACHMENT_TOKEN_PATTERN.matcher(userMessage);
        StringBuffer expanded = new StringBuffer();
        boolean found = false;
        boolean hasImageAttachment = false;

        java.util.List<AttachmentTokenMatch> tokenMatches = new java.util.ArrayList<>();

        while (matcher.find()) {
            found = true;
            String attachmentId = matcher.group(1);
            var metaOpt = attachmentStore.getMeta(attachmentId);
            var base64Opt = attachmentStore.getBase64(attachmentId);
            if (metaOpt.isEmpty() || base64Opt.isEmpty()) {
                return AttachmentExpansionResult.error("attachmentId が無効です: " + attachmentId);
            }

            BinaryAttachmentStore.AttachmentMetadata meta = metaOpt.get();
            boolean image = meta.mimeType() != null && meta.mimeType().toLowerCase().startsWith("image/");
            hasImageAttachment = hasImageAttachment || image;
            tokenMatches.add(new AttachmentTokenMatch(
                    matcher.start(),
                    matcher.end(),
                    attachmentId,
                    meta.filename(),
                    meta.mimeType(),
                    base64Opt.get(),
                    image));

            String replacement = "attachment(id=%s,name=\"".formatted(attachmentId) + meta.filename()
                    + "\",mime=\"%s\",base64=\"".formatted(meta.mimeType()) + base64Opt.get() + "\")";
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
        }

        if (!found) {
            return AttachmentExpansionResult.success(userMessage);
        }

        if (hasImageAttachment) {
            return AttachmentExpansionResult.success(buildMultimodalJsonMessage(userMessage, tokenMatches));
        }

        matcher.appendTail(expanded);
        return AttachmentExpansionResult.success(expanded.toString());
    }

    private String buildMultimodalJsonMessage(String userMessage, java.util.List<AttachmentTokenMatch> tokenMatches) {
        String provider = resolveProviderForMultimodal();
        if (PROVIDER_GEMINI.equals(provider)) {
            return buildGeminiMultimodalJson(userMessage, tokenMatches);
        }
        return buildOpenAiMultimodalJson(userMessage, tokenMatches);
    }

    private String buildOpenAiMultimodalJson(String userMessage, java.util.List<AttachmentTokenMatch> tokenMatches) {
        JsonObject payload = new JsonObject();
        JsonArray content = new JsonArray();

        int cursor = 0;
        for (AttachmentTokenMatch token : tokenMatches) {
            String text = userMessage.substring(cursor, token.start());
            if (!text.isEmpty()) {
                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", text);
                content.add(textPart);
            }

            if (token.image()) {
                JsonObject imagePart = new JsonObject();
                imagePart.addProperty("type", "image_url");
                JsonObject imageUrl = new JsonObject();
                imageUrl.addProperty("url", "data:" + token.mimeType() + ";base64," + token.base64());
                imagePart.add("image_url", imageUrl);
                content.add(imagePart);
            } else {
                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", legacyAttachmentText(token));
                content.add(textPart);
            }
            cursor = token.end();
        }

        String tail = userMessage.substring(cursor);
        if (!tail.isEmpty()) {
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            textPart.addProperty("text", tail);
            content.add(textPart);
        }

        payload.addProperty("format", "openai_chat_completions_multimodal");
        payload.add("content", content);
        return payload.toString();
    }

    private String buildGeminiMultimodalJson(String userMessage, java.util.List<AttachmentTokenMatch> tokenMatches) {
        JsonObject payload = new JsonObject();
        JsonArray parts = new JsonArray();

        int cursor = 0;
        for (AttachmentTokenMatch token : tokenMatches) {
            String text = userMessage.substring(cursor, token.start());
            if (!text.isEmpty()) {
                JsonObject textPart = new JsonObject();
                textPart.addProperty("text", text);
                parts.add(textPart);
            }

            if (token.image()) {
                JsonObject inlineDataPart = new JsonObject();
                JsonObject inlineData = new JsonObject();
                inlineData.addProperty("mime_type", token.mimeType());
                inlineData.addProperty("data", token.base64());
                inlineDataPart.add("inline_data", inlineData);
                parts.add(inlineDataPart);
            } else {
                JsonObject textPart = new JsonObject();
                textPart.addProperty("text", legacyAttachmentText(token));
                parts.add(textPart);
            }
            cursor = token.end();
        }

        String tail = userMessage.substring(cursor);
        if (!tail.isEmpty()) {
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", tail);
            parts.add(textPart);
        }

        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        payload.addProperty("format", "gemini_generate_content_multimodal");
        payload.add("contents", contents);
        return payload.toString();
    }

    private String resolveProviderForMultimodal() {
        String provider = currentSession == null ? "" : currentSession.provider();
        if (provider != null && !provider.isBlank()) {
            String normalized = provider.trim().toLowerCase();
            if (PROVIDER_GEMINI.equals(normalized) || PROVIDER_OPENAI.equals(normalized)) {
                return normalized;
            }
        }
        if (chatService instanceof GeminiNativeChatService) {
            return PROVIDER_GEMINI;
        }
        return PROVIDER_OPENAI;
    }

    private String legacyAttachmentText(AttachmentTokenMatch token) {
        return "attachment(id=%s,name=\"".formatted(token.id()) + token.filename()
                + "\",mime=\"%s\",base64=\"".formatted(token.mimeType()) + token.base64() + "\")";
    }

    private record AttachmentTokenMatch(
            int start,
            int end,
            String id,
            String filename,
            String mimeType,
            String base64,
            boolean image) {
    }

    /**
     * アシスタントの応答に添付ファイルトークンが含まれている場合、LLM に再生成を促す。
     * 
     * @param userMessage      ユーザーメッセージ
     * @param assistantMessage アシスタントメッセージ
     * @return トークンが含まれていないアシスタントメッセージ。再生成に失敗した場合はエラーメッセージを返す。
     */
    private String guardAssistantResponse(String userMessage, String assistantMessage) {
        if (assistantMessage == null || assistantMessage.isBlank()) {
            return assistantMessage;
        }
        if (!ASSISTANT_ATTACHMENT_TOKEN_PATTERN.matcher(assistantMessage).find()) {
            return assistantMessage;
        }

        String retryInstruction = "\n\n重要: 回答には [[ATTACH:...]] のようなトークンを含めず、"
                + "最終的な要約や説明本文だけを日本語で返してください。";
        String retried = chatService.replyToWithHistory(conversationHistory, userMessage + retryInstruction);
        if (retried == null || retried.isBlank()) {
            return "(error) 要約結果の生成に失敗しました。もう一度お試しください。";
        }
        if (ASSISTANT_ATTACHMENT_TOKEN_PATTERN.matcher(retried).find()) {
            return "(error) 要約結果の生成に失敗しました。もう一度お試しください。";
        }
        return retried;
    }

    /**
     * 添付ファイルトークンの展開結果を表すレコードクラス。
     * 
     * @param success         展開が成功したかどうか
     * @param expandedMessage 展開後のメッセージ（成功時のみ有効）
     * @param errorMessage    エラーメッセージ（失敗時のみ
     */
    private record AttachmentExpansionResult(boolean success, String expandedMessage, String errorMessage) {
        static AttachmentExpansionResult success(String message) {
            return new AttachmentExpansionResult(true, message, "");
        }

        static AttachmentExpansionResult error(String message) {
            return new AttachmentExpansionResult(false, "", message);
        }
    }

    /**
     * 会話履歴からトランスクリプト文字列を生成するユーティリティ。
     * 
     * @param history 会話履歴
     * @return トランスクリプト文字列
     */
    private static String formatTranscript(List<ChatMessage> history) {
        if (history.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : history) {
            jp.euks.myagent2.chat.ChatRole r = jp.euks.myagent2.chat.ChatRole.parse(message.role());
            String role = switch (r) {
                case USER -> "You";
                case ASSISTANT -> "Assistant";
                case SYSTEM -> "System";
                case TOOL -> "Tool";
                default -> message.role();
            };
            sb.append(role).append(": ").append(message.content()).append("\n");
            if (r == jp.euks.myagent2.chat.ChatRole.ASSISTANT) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
