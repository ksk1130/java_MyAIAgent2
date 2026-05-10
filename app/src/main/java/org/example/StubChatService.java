package org.example;

/**
 * API キー未設定時のフォールバック実装。外部呼び出しを行わず簡易応答を返す。
 */
public class StubChatService implements ChatService {
    /**
     * 受け取ったメッセージをそのまま返す簡易実装。
     *
     * @param userMessage ユーザーのメッセージ
     * @return スタブ応答文字列
     */
    @Override
    public String replyTo(String userMessage) {
        return "(stub) 受け取りました: " + userMessage;
    }
}