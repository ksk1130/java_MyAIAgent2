package jp.euks.myagent2.session;

import java.util.List;

/**
 * 会話セッションの永続化を担当するインターフェース。
 */
public interface ConversationStore {
    /**
     * 最新セッションを読み込む。存在しない場合は新規セッションを返す。
     *
     * @return 読み込み済みまたは新規作成されたセッション
     */
    ConversationSession loadLatestOrCreate();

    /**
     * 指定セッションを読み込む。存在しない場合は新規作成したセッションを返す。
     *
     * @param sessionId 読み込み対象のセッションID
     * @return 読み込み済みまたは新規作成されたセッション
     */
    ConversationSession loadByIdOrCreate(String sessionId);

    /**
     * セッション一覧を更新日時の降順で返す。
     *
     * @return セッション要約一覧
     */
    List<SessionSummary> listSessions();

    /**
     * 新規セッションを作成して保存する。
     *
     * @return 作成されたセッション
     */
    ConversationSession createNewSession();

    /**
     * 初期作業ディレクトリを指定して新規セッションを作成・保存する。
     *
     * @param initialWorkingDirectory 初期作業ディレクトリのパス文字列
     * @return 作成されたセッション
     */
    default ConversationSession createNewSession(String initialWorkingDirectory) {
        ConversationSession session = createNewSession();
        if (initialWorkingDirectory != null && !initialWorkingDirectory.isBlank()) {
            session.setWorkingDirectory(initialWorkingDirectory);
            save(session);
        }
        return session;
    }

    /**
     * 指定セッションを保存する。
     *
     * @param session 保存対象セッション
     */
    void save(ConversationSession session);

    /**
     * 指定セッションを削除する。存在しない場合は何もしない。
     *
     * @param sessionId 削除対象セッションID
     */
    void deleteSession(String sessionId);
}
