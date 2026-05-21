package jp.euks.myagent2.session;

/**
 * 履歴一覧表示用のセッション要約。
 *
 * @param sessionId セッションID
 * @param title     表示タイトル
 * @param updatedAt 最終更新日時（ISO-8601文字列）
 */
public record SessionSummary(String sessionId, String title, String updatedAt) {
    @Override
    public String toString() {
        return (title == null || title.isBlank()) ? "New Chat" : title;
    }
}
