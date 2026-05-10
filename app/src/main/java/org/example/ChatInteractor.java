package org.example;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * UI 層と ChatService の仲介を行うインタラクタ。
 * 手動ツールコマンドを優先的に処理し、そうでなければチャットサービスへ履歴付きで委譲する。
 */
public class ChatInteractor {
    private static final String TOOL_RESULTS_BEGIN_MARKER = "<<TOOL_RESULTS_BEGIN>>";
    private static final String TOOL_RESULTS_END_MARKER = "<<TOOL_RESULTS_END>>";

    private final ChatService chatService;
    private final ManualToolExecutor manualToolExecutor;
    private final ConversationStore conversationStore;
    private final StringBuilder transcript;
    private final List<ChatMessage> conversationHistory;
    private final ConversationSession currentSession;

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
     * @param chatService         チャット応答サービス
     * @param manualToolExecutor  手動ツール実行器
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
            }
            this.conversationHistory.addAll(loadedSession.messages());
            this.transcript.append(formatTranscript(this.conversationHistory));
        }
        this.currentSession = loadedSession;
    }

    public String onUserMessage(String rawInput) {
        /**
         * ユーザーの入力を処理し、画面に表示する形式のターン文字列を返す。
         * - /clear コマンドは最優先で履歴を完全削除
         * - 入力が手動ツールコマンドならその実行結果を返す
         * - そうでなければ `chatService.replyToWithHistory` を呼ぶ
         *
         * @param rawInput ユーザー入力（null/空は無視される）
         * @return 画面に追加するターン文字列
         */
        if (rawInput == null) {
            return "";
        }

        String userMessage = rawInput.trim();
        if (userMessage.isEmpty()) {
            return "";
        }

        // /clear コマンドは最優先で履歴を完全削除
        if (userMessage.equalsIgnoreCase("/clear")) {
            conversationHistory.clear();
            transcript.setLength(0);
            chatService.clearMemory();
            if (currentSession != null) {
                currentSession.replaceMessages(List.of());
                if (conversationStore != null) {
                    conversationStore.save(currentSession);
                }
            }
            return "You: /clear\nAssistant: 会話履歴を削除しました\n\n";
        }

        String baseAssistantMessage = manualToolExecutor.tryExecute(userMessage)
            .orElseGet(() -> chatService.replyToWithHistory(conversationHistory, userMessage));
        String assistantMessage = baseAssistantMessage;
        String toolResultsText = "";

        // /tool setdir 成功時は ChatService 側のツール基点も同期する
        syncWorkingDirectoryIfSetdirSucceeded(userMessage, assistantMessage);

        // LLM が tool calling を実行した場合、tracker から結果を取得して表示に追加
        ToolExecutionTracker tracker = chatService.getToolExecutionTracker();
        if (tracker != null) {
            var executions = tracker.getExecutions();
            if (!executions.isEmpty()) {
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
                    assistantMessage = toolResultsText + "\n\n" + baseAssistantMessage;
                }
            }
        }

        conversationHistory.add(new ChatMessage("user", userMessage));
        conversationHistory.add(new ChatMessage("assistant", assistantMessage));
        persistConversation();

        String turnText;
        if (!toolResultsText.isEmpty()) {
            // ツール結果と最終回答の境界を明示して、UI 側で正しく分離できるようにする。
            turnText = "You: " + userMessage + "\n"
                + "Assistant: " + TOOL_RESULTS_BEGIN_MARKER + "\n"
                + toolResultsText + "\n"
                + "Assistant: " + TOOL_RESULTS_END_MARKER + "\n"
                + "Assistant: " + baseAssistantMessage + "\n\n";
        } else {
            turnText = "You: " + userMessage + "\n"
                + "Assistant: " + assistantMessage + "\n\n";
        }
        transcript.append(turnText);
        return turnText;
    }

    public String getTranscript() {
        return transcript.toString();
    }

    /**
     * ストリーミングでユーザーメッセージを処理する。
     * <p>
     * - 手動ツールコマンドは同期的に処理し、onToken に全テキストを一括通知する。
     * - LLM 呼び出しは {@link ChatService#streamReplyToWithHistory} 経由でストリーミングし、
     *   各トークンを onToken に逐次通知する。
     * - ストリーミング完了時にツール実行結果を付加した上で transcript を更新し onComplete を呼ぶ。
     *
     * @param rawInput  ユーザー入力（null/空は無視）
     * @param onToken   トークン到着時コールバック（UI スレッドへの委譲は呼び出し元の責務）
     * @param onComplete 処理完了時コールバック
     * @param onError   エラー時コールバック
     */
    public void startUserMessageStream(
            String rawInput,
            Consumer<String> onToken,
            Runnable onComplete,
            Consumer<Throwable> onError) {

        if (rawInput == null) {
            onComplete.run();
            return;
        }
        String userMessage = rawInput.trim();
        if (userMessage.isEmpty()) {
            onComplete.run();
            return;
        }

        // /clear コマンドは最優先で履歴を完全削除
        if (userMessage.equalsIgnoreCase("/clear")) {
            conversationHistory.clear();
            transcript.setLength(0);
            chatService.clearMemory();
            if (currentSession != null) {
                currentSession.replaceMessages(List.of());
                if (conversationStore != null) {
                    conversationStore.save(currentSession);
                }
            }
            String turnText = "You: /clear\nAssistant: 会話履歴を削除しました\n\n";
            onToken.accept(turnText);
            onComplete.run();
            return;
        }

        // 手動ツールコマンドは同期的に処理してストリーミング不要
        var manualResult = manualToolExecutor.tryExecute(userMessage);
        if (manualResult.isPresent()) {
            String assistantMessage = manualResult.get();
            syncWorkingDirectoryIfSetdirSucceeded(userMessage, assistantMessage);
            conversationHistory.add(new ChatMessage("user", userMessage));
            conversationHistory.add(new ChatMessage("assistant", assistantMessage));
            persistConversation();
            String turnText = "You: " + userMessage + "\n"
                + "Assistant: " + assistantMessage + "\n\n";
            transcript.append(turnText);
            onToken.accept(turnText);
            onComplete.run();
            return;
        }

        // LLM ストリーミング: "You: ..." ヘッダーをまず通知
        String youHeader = "You: " + userMessage + "\nAssistant: ";
        onToken.accept(youHeader);

        chatService.streamReplyToWithHistory(
            conversationHistory,
            userMessage,
            onToken,  // 各トークンを UI に直接通知
            fullLlmText -> {
                // ツール実行結果プレフィックスを付加
                String assistantMessage = fullLlmText;
                String toolResultsText = "";
                ToolExecutionTracker tracker = chatService.getToolExecutionTracker();
                if (tracker != null) {
                    var executions = tracker.getExecutions();
                    if (!executions.isEmpty()) {
                        StringBuilder toolResults = new StringBuilder();
                        for (var exec : executions) {
                            if (!toolResults.isEmpty()) {
                                toolResults.append("\n\n");
                            }
                            toolResults.append(exec.format());
                        }
                        if (!toolResults.isEmpty()) {
                            toolResultsText = toolResults.toString();
                            assistantMessage = toolResults.toString() + "\n\n" + fullLlmText;
                        }
                    }
                }

                syncWorkingDirectoryIfSetdirSucceeded(userMessage, assistantMessage);
                conversationHistory.add(new ChatMessage("user", userMessage));
                conversationHistory.add(new ChatMessage("assistant", assistantMessage));
                persistConversation();

                // transcript はストリーミング中は更新せず、完了時に全ターンテキストを追加
                String turnText;
                if (!toolResultsText.isEmpty()) {
                    turnText = "You: " + userMessage + "\n"
                        + "Assistant: " + TOOL_RESULTS_BEGIN_MARKER + "\n"
                        + toolResultsText + "\n"
                        + "Assistant: " + TOOL_RESULTS_END_MARKER + "\n"
                        + "Assistant: " + fullLlmText + "\n\n";
                } else {
                    turnText = "You: " + userMessage + "\n"
                        + "Assistant: " + assistantMessage + "\n\n";
                }
                transcript.append(turnText);
                onComplete.run();
            },
            onError
        );
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
        if (dir == null) return;
        chatService.setWorkingDirectory(dir);
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
        return currentSession == null ? "" : currentSession.sessionId();
    }

    private void persistConversation() {
        if (conversationStore == null || currentSession == null) {
            return;
        }

        // 現在の ChatService の作業ディレクトリをセッションに同期して保存
        java.nio.file.Path currentWd = chatService.getWorkingDirectory();
        if (currentWd != null) {
            currentSession.setWorkingDirectory(currentWd.toString());
        }
        currentSession.replaceMessages(conversationHistory);
        conversationStore.save(currentSession);
    }

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
            chatService.setWorkingDirectory(Path.of(pathText));
        } catch (InvalidPathException ignored) {
            // setdir 側で既にエラー扱いのため、ここでは何もしない
        }
    }

    private static String formatTranscript(List<ChatMessage> history) {
        if (history.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : history) {
            String role = switch (message.role()) {
                case "user" -> "You";
                case "assistant" -> "Assistant";
                default -> message.role();
            };
            sb.append(role).append(": ").append(message.content()).append("\n");
            if ("assistant".equals(message.role())) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}