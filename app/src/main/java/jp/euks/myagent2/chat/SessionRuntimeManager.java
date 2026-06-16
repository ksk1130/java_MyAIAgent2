package jp.euks.myagent2.chat;

import java.util.Objects;
import jp.euks.myagent2.session.ConversationStore;
import jp.euks.myagent2.session.ConversationSession;
import jp.euks.myagent2.tools.DefaultManualToolExecutor;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * シンプルな LRU を用いた SessionRuntime の管理。最大保持数を超えたら古いものから破棄する。
 */
public class SessionRuntimeManager {
    private final Map<String, SessionRuntime> map;
    private final int maxEntries;
    private final ConversationStore conversationStore;
    private final Path baseWorkDir;

    /**
     * SessionRuntimeManager を作成します。
     * 
     * @param maxEntries  最大保持セッション数（1以上）
     * @param store       会話履歴の永続化ストア
     * @param baseWorkDir 作業ディレクトリのベースパス
     */
    public SessionRuntimeManager(int maxEntries, ConversationStore store, Path baseWorkDir) {
        this.maxEntries = Math.max(1, maxEntries);
        this.conversationStore = store;
        this.baseWorkDir = baseWorkDir;
        this.map = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * 指定したセッションIDの SessionRuntime を取得します。存在しない場合は新規作成します。
     * セッションに保存されたプロバイダーがあれば、それを使用してChatServiceを生成します。
     * 
     * @param sessionId セッションID
     * @return SessionRuntime インスタンス。sessionId が null または空白の場合は null
     */
    public synchronized SessionRuntime getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        SessionRuntime rt = map.get(sessionId);
        if (rt != null) {
            rt.touch();
            return rt;
        }

        // 新規生成: セッションのプロバイダーを使ってChatServiceを生成する
        ConversationSession session = conversationStore.loadByIdOrCreate(sessionId);
        String provider = (session != null) ? session.provider() : "";
        ChatService svc = ChatServiceFactory.createForSessionWithProvider(baseWorkDir, provider);
        ChatInteractor interactor = new ChatInteractor(svc, new DefaultManualToolExecutor(), conversationStore,
                sessionId);
        rt = new SessionRuntime(sessionId, svc, interactor);
        map.put(sessionId, rt);
        evictIfNeeded();
        return rt;
    }

    /**
     * 指定したセッションIDの SessionRuntime を取得します。存在しない場合は Optional.empty() を返します。
     * 
     * @param sessionId セッションID
     * @return SessionRuntime インスタンス。存在しない場合は Optional.empty()。sessionId が null
     *         または空白の場合も Optional.empty() を返す。
     */
    public synchronized Optional<SessionRuntime> get(String sessionId) {
        return Optional.ofNullable(map.get(sessionId));
    }

    /**
     * 指定したセッションIDの SessionRuntime を削除します。存在しない場合は何もしません。
     * 
     * @param sessionId セッションID。null または空白の場合は何もしません。
     * @return 削除された SessionRuntime インスタンス。存在しない場合は Optional.empty()。
     */
    public synchronized void remove(String sessionId) {
        SessionRuntime removed = map.remove(sessionId);
        if (removed != null) {
            // 将来的にリソース解放が必要ならここで処理する
        }
    }

    /**
     * LRU を用いて、最大保持数を超えた場合に古い SessionRuntime を退避します。退避の際には busy でないものを優先的に選びます。
     */
    private void evictIfNeeded() {
        while (map.size() > maxEntries) {
            // LRU の先頭から busy でないものを探して退避する
            String candidateKey = null;
            for (Map.Entry<String, SessionRuntime> e : map.entrySet()) {
                SessionRuntime r = e.getValue();
                try {
                    if (r.getInteractor() != null && r.getInteractor().isBusy()) {
                        continue;
                    }
                } catch (Exception ignored) {
                }
                candidateKey = e.getKey();
                break;
            }
            if (Objects.isNull(candidateKey)) {
                // 全部 busy の可能性があるため一旦退避をやめる
                break;
            }
            SessionRuntime eldest = map.remove(candidateKey);
            if (eldest != null) {
                try {
                    if (eldest.getInteractor() != null) {
                        // Use saveWithoutTouch to avoid updating session.updatedAt on eviction
                        eldest.getInteractor().saveWithoutTouch();
                    }
                } catch (Exception ignored) {
                }
                try {
                    if (eldest.getChatService() != null) {
                        eldest.getChatService().clearMemory();
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * テスト用: 明示的に SessionRuntime を挿入する（LRU 順序を更新して evict を行う）。
     */
    public synchronized void putForTest(SessionRuntime rt) {
        if (Objects.isNull(rt))
            return;
        map.put(rt.getSessionId(), rt);
        evictIfNeeded();
    }
}
