package jp.euks.myagent2.chat;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import jp.euks.myagent2.tools.*;

/**
 * チャット応答を生成するための抽象インターフェース。
 * 実装は単一のメッセージ応答を返す `replyTo` を提供し、会話履歴付きの呼び出しは
 * `replyToWithHistory` をオーバーライドして対応できる。
 */
public interface ChatService {
    /**
     * 単一のユーザーメッセージに対する応答を返す。
     *
     * @param userMessage ユーザーのメッセージ本文
     * @return アシスタントの応答テキスト
     */
    String replyTo(String userMessage);

    /**
     * 会話履歴を含めて応答を生成する。既定実装は `replyTo` を呼び出す。
     * 実装側が履歴を利用する場合はこのメソッドをオーバーライドする。
     *
     * @param history     これまでの会話履歴（`ChatMessage` のリスト）
     * @param userMessage 最新のユーザー発話
     * @return アシスタントの応答テキスト
     */
    default String replyToWithHistory(List<ChatMessage> history, String userMessage) {
        return replyTo(userMessage);
    }

    /**
     * 会話履歴を含めて応答をストリーミングで生成する。既定実装は一括応答を経由する。
     * ストリーミング非対応の実装では `replyToWithHistory` の結果を全量で onToken に渡す。
     *
     * @param history     これまでの会話履歴
     * @param userMessage 最新のユーザー発話
     * @param onToken     トークン到着時に呼ばれるコールバック（LLM テキストのみ、ツール結果除く）
     * @param onComplete  応答全体が完了したときに呼ばれるコールバック（引数は LLM テキスト全文）
     * @param onError     エラー時に呼ばれるコールバック
     */
    /**
     * 進捗通知対応版（ツール実行名を逐次通知）。
     * 既定実装は onProgress を無視し、従来通り一括応答。
     */
    default void streamReplyToWithHistory(
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onToken,
            Consumer<String> onComplete,
            Consumer<Throwable> onError,
            Consumer<String> onProgress) {
        try {
            String result = replyToWithHistory(history, userMessage);
            onToken.accept(result);
            onComplete.accept(result);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /**
     * 従来のstreamReplyToWithHistory（onProgressなし）
     */
    default void streamReplyToWithHistory(
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onToken,
            Consumer<String> onComplete,
            Consumer<Throwable> onError) {
        streamReplyToWithHistory(history, userMessage, onToken, onComplete, onError, null);
    }

    /**
     * システムプロンプトを設定する。既定実装は何もしない。
     *
     * @param prompt 新しいシステムプロンプト
     */
    default void setSystemPrompt(String prompt) {
    }

    /**
     * 現在のシステムプロンプトを返す。既定実装は空文字を返す。
     *
     * @return 現在のシステムプロンプト
     */
    default String getSystemPrompt() {
        return "";
    }

    /**
     * Tool 実行記録トラッカーを返す。既定実装は null を返す。
     * LLM が tool calling を実行した場合、トラッカーに記録される。
     *
     * @return ToolExecutionTracker、または null
     */
    default ToolExecutionTracker getToolExecutionTracker() {
        return null;
    }

    /**
     * ツールの作業ディレクトリを更新する。既定実装は何もしない。
     * `/tool setdir` 成功時に呼び出されることを想定。
     *
     * @param dir 新しい作業ディレクトリ
     */
    default void setWorkingDirectory(Path dir) {
    }
    
    /**
     * 現在の作業ディレクトリを返す。既定実装は null を返す。
     * 実装側が管理している場合はオーバーライドして現在値を返すこと。
     *
     * @return 現在の作業ディレクトリ、未設定時は null
     */
    default Path getWorkingDirectory() {
        return null;
    }

    /**
     * チャットメモリ（会話履歴）をクリアする。既定実装は何もしない。
     * /clear コマンド等で呼び出されることを想定。
     */
    default void clearMemory() {
    }

    /**
     * チャットメモリに履歴を復元する。既定実装は何もしない。
     * セッション切替時に JSON から読み出した履歴を LLM 側へ再投入する用途を想定する。
     *
     * @param history 復元したい会話履歴
     */
    default void restoreMemory(List<ChatMessage> history) {
    }
}