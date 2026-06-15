package jp.euks.myagent2.session;



import java.util.Objects;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jp.euks.myagent2.chat.*;

/**
 * 会話履歴を1セッション単位で保持するデータモデル。
 */
public final class ConversationSession {
    private String sessionId;
    private String title;
    private boolean titleManuallySet;
    private String createdAt;
    private String updatedAt;
    private List<ChatMessage> messages;
    private String workingDirectory;
    /** トランスクリプト文字列（ツール結果マーカーを含む）。JSON に永続化する。 */
    private String transcript;
    /** このセッションで使用するプロバイダー（"openai" または "gemini"、未設定時は既定値を使用）。 */
    private String provider;
    /** セッション累積のトークン使用量（永続化対象） */
    private long totalInputTokens;
    private long totalOutputTokens;

    /**
     * デフォルトコンストラクタ（シリアライズ用）。
     */
    ConversationSession() {
        this("", "", "", "", new ArrayList<>(), "", null, false, "");
    }

    /**
     * セッションの基本情報を指定して新規インスタンスを作成します。
     *
     * @param sessionId セッション ID
     * @param title     セッションタイトル
     * @param createdAt 作成日時（ISO-8601 文字列）
     * @param updatedAt 更新日時（ISO-8601 文字列）
     * @param messages  メッセージのリスト
     */
    public ConversationSession(
            String sessionId,
            String title,
            String createdAt,
            String updatedAt,
            List<ChatMessage> messages) {
        this(sessionId, title, createdAt, updatedAt, messages, "", null);
    }

    /**
     * 作業ディレクトリを含むセッションを作成します。
     *
     * @param sessionId        セッション ID
     * @param title            セッションタイトル
     * @param createdAt        作成日時
     * @param updatedAt        更新日時
     * @param messages         メッセージリスト
     * @param workingDirectory 作業ディレクトリパス
     */
    public ConversationSession(
            String sessionId,
            String title,
            String createdAt,
            String updatedAt,
            List<ChatMessage> messages,
            String workingDirectory) {
        this(sessionId, title, createdAt, updatedAt, messages, workingDirectory, null);
    }

    /**
     * フルコンストラクタ。
     *
     * @param sessionId        セッション ID
     * @param title            セッションタイトル
     * @param createdAt        作成日時
     * @param updatedAt        更新日時
     * @param messages         メッセージリスト
     * @param workingDirectory 作業ディレクトリ
     * @param transcript       保存済みトランスクリプト文字列（未保存時は null）
     */
    public ConversationSession(
            String sessionId,
            String title,
            String createdAt,
            String updatedAt,
            List<ChatMessage> messages,
            String workingDirectory,
            String transcript) {
        this(sessionId, title, createdAt, updatedAt, messages, workingDirectory, transcript, false);
    }

    /**
     * フルコンストラクタ（タイトル手動更新フラグ・プロバイダー付き）。
     *
     * @param sessionId        セッション ID
     * @param title            セッションタイトル
     * @param createdAt        作成日時
     * @param updatedAt        更新日時
     * @param messages         メッセージリスト
     * @param workingDirectory 作業ディレクトリ
     * @param transcript       保存済みトランスクリプト文字列（未保存時は null）
     * @param titleManuallySet タイトルをユーザーが手動設定済みかどうか
     * @param provider         このセッションで使用するプロバイダー（"openai" または "gemini"、未設定時は ""）
     */
    public ConversationSession(
            String sessionId,
            String title,
            String createdAt,
            String updatedAt,
            List<ChatMessage> messages,
            String workingDirectory,
            String transcript,
            boolean titleManuallySet,
            String provider) {
        this.sessionId = sessionId;
        this.title = title;
        this.titleManuallySet = titleManuallySet;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messages = new ArrayList<>(Objects.isNull(messages) ? List.of() : messages);
        this.workingDirectory = Objects.isNull(workingDirectory) ? "" : workingDirectory;
        this.transcript = transcript;
        this.provider = Objects.isNull(provider) ? "" : provider;
        this.totalInputTokens = 0L;
        this.totalOutputTokens = 0L;
    }

    /**
     * フルコンストラクタ（タイトル手動更新フラグ付き）。
     *
     * @param sessionId        セッション ID
     * @param title            セッションタイトル
     * @param createdAt        作成日時
     * @param updatedAt        更新日時
     * @param messages         メッセージリスト
     * @param workingDirectory 作業ディレクトリ
     * @param transcript       保存済みトランスクリプト文字列（未保存時は null）
     * @param titleManuallySet タイトルをユーザーが手動設定済みかどうか
     */
    public ConversationSession(
            String sessionId,
            String title,
            String createdAt,
            String updatedAt,
            List<ChatMessage> messages,
            String workingDirectory,
            String transcript,
            boolean titleManuallySet) {
        this(sessionId, title, createdAt, updatedAt, messages, workingDirectory, transcript, titleManuallySet, "");
    }

    /**
     * 新規セッションを生成するユーティリティ。
     *
     * @param clock 時刻取得用の Clock
     * @return 新しく作成された ConversationSession
     */
    public static ConversationSession createNew(Clock clock) {
        String now = OffsetDateTime.now(clock).toString();
        return new ConversationSession(
                UUID.randomUUID().toString(),
                "New Chat",
                now,
                now,
                new ArrayList<>(),
                "");
    }

    public String sessionId() {
        return sessionId;
    }

    public String title() {
        return title;
    }

    /**
     * セッションタイトルを取得します。
     *
     * @return セッションタイトル
     */
    public String getTitle() {
        return title;
    }

    /**
     * セッションタイトルを手動で更新します。
     *
     * @param newTitle 新しいタイトル
     */
    public void setTitle(String newTitle) {
        String normalized = Objects.isNull(newTitle) ? "" : newTitle.trim();
        this.title = normalized.isEmpty() ? "New Chat" : normalized;
        this.titleManuallySet = true;
    }

    public String createdAt() {
        return createdAt;
    }

    public String updatedAt() {
        return updatedAt;
    }

    public List<ChatMessage> messages() {
        return List.copyOf(Objects.isNull(messages) ? List.of() : messages);
    }

    public String workingDirectory() {
        return Objects.isNull(workingDirectory) ? "" : workingDirectory;
    }

    /**
     * 作業ディレクトリを設定します。
     *
     * @param path 作業ディレクトリのパス文字列
     */
    public void setWorkingDirectory(String path) {
        this.workingDirectory = Objects.isNull(path) ? "" : path;
    }

    /**
     * トランスクリプト文字列を取得します。
     *
     * @return トランスクリプト文字列（未保存時は null）
     */
    public String transcript() {
        return transcript;
    }

    /**
     * トランスクリプト文字列を設定します。
     *
     * @param transcript 設定するトランスクリプト文字列
     */
    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    /**
     * このセッションで使用するプロバイダーを取得します。
     *
     * @return プロバイダー（"openai" または "gemini"、未設定時は空文字）
     */
    public String provider() {
        return Objects.isNull(provider) ? "" : provider;
    }

    /**
     * セッション累積の入力トークン数を返す。
     *
     * @return 累積入力トークン数
     */
    public synchronized long totalInputTokens() {
        return totalInputTokens;
    }

    /**
     * セッション累積の出力トークン数を返す。
     *
     * @return 累積出力トークン数
     */
    public synchronized long totalOutputTokens() {
        return totalOutputTokens;
    }

    /**
     * セッションに対してトークン使用量を追加する（累積）。
     *
     * @param input  入力トークン数
     * @param output 出力トークン数
     */
    public synchronized void addTokenUsage(long input, long output) {
        if (input > 0) this.totalInputTokens += input;
        if (output > 0) this.totalOutputTokens += output;
    }

    /**
     * 累積トークン使用量をリセットする（/clear 時に呼び出す）。
     */
    public synchronized void clearTokenUsage() {
        this.totalInputTokens = 0L;
        this.totalOutputTokens = 0L;
    }

    /**
     * このセッションで使用するプロバイダーを設定します。
     *
     * @param provider 新しいプロバイダー（"openai" または "gemini"）
     */
    public void setProvider(String provider) {
        this.provider = Objects.isNull(provider) ? "" : provider;
    }

    /**
     * メッセージ一覧を置換し、タイトルを推定して更新します。
     *
     * @param newMessages 新しいメッセージリスト
     */
    public void replaceMessages(List<ChatMessage> newMessages) {
        this.messages = new ArrayList<>(Objects.isNull(newMessages) ? List.of() : newMessages);
        if (!titleManuallySet) {
            this.title = inferTitle(this.messages, this.title);
        }
    }

    /**
     * 更新日時を現在時刻に更新します。
     *
     * @param clock 時刻取得用の Clock
     */
    public void touch(Clock clock) {
        this.updatedAt = OffsetDateTime.now(clock).toString();
    }

    /**
     * メッセージの最初の user 投稿からタイトルを推定します。
     *
     * @param messages メッセージリスト
     * @param fallback タイトルが推定できない場合の代替文字列
     * @return 推定されたタイトル
     */
    private static String inferTitle(List<ChatMessage> messages, String fallback) {
        for (ChatMessage message : messages) {
            jp.euks.myagent2.chat.ChatRole r = jp.euks.myagent2.chat.ChatRole.parse(message.role());
            if (r != jp.euks.myagent2.chat.ChatRole.USER) {
                continue;
            }
            String content = Objects.isNull(message.content()) ? "" : message.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() <= 32) {
                return content;
            }
            return content.substring(0, 32) + "...";
        }
        return (fallback == null || fallback.isBlank()) ? "New Chat" : fallback;
    }
}



