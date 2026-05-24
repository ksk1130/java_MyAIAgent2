package jp.euks.myagent2.chat;

import jp.euks.myagent2.session.ConversationStore;
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

    public SessionRuntimeManager(int maxEntries, ConversationStore store, Path baseWorkDir) {
        this.maxEntries = Math.max(1, maxEntries);
        this.conversationStore = store;
        this.baseWorkDir = baseWorkDir;
        this.map = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized SessionRuntime getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        SessionRuntime rt = map.get(sessionId);
        if (rt != null) {
            rt.touch();
            return rt;
        }

        // 新規生成: ChatService はファクトリ経由で作る
        ChatService svc = ChatServiceFactory.createForSession(baseWorkDir);
        ChatInteractor interactor = new ChatInteractor(svc, new DefaultManualToolExecutor(), conversationStore, sessionId);
        rt = new SessionRuntime(sessionId, svc, interactor);
        map.put(sessionId, rt);
        evictIfNeeded();
        return rt;
    }

    public synchronized Optional<SessionRuntime> get(String sessionId) {
        return Optional.ofNullable(map.get(sessionId));
    }

    public synchronized void remove(String sessionId) {
        SessionRuntime removed = map.remove(sessionId);
        if (removed != null) {
            // 将来的にリソース解放が必要ならここで処理する
        }
    }

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
            if (candidateKey == null) {
                // 全部 busy の可能性があるため一旦退避をやめる
                break;
            }
            SessionRuntime eldest = map.remove(candidateKey);
            if (eldest != null) {
                try {
                    if (eldest.getInteractor() != null) {
                        eldest.getInteractor().save();
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
        if (rt == null) return;
        map.put(rt.getSessionId(), rt);
        evictIfNeeded();
    }
}
