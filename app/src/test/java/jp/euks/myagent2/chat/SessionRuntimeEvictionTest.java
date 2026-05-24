package jp.euks.myagent2.chat;

import jp.euks.myagent2.session.ConversationSession;
import jp.euks.myagent2.session.ConversationStore;
import org.junit.Test;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import jp.euks.myagent2.tools.DefaultManualToolExecutor;

import static org.junit.Assert.*;

public class SessionRuntimeEvictionTest {

    static class SpyConversationStore implements ConversationStore {
        final Set<String> saved = new HashSet<>();

        @Override
        public ConversationSession loadLatestOrCreate() { return null; }

        @Override
        public ConversationSession loadByIdOrCreate(String sessionId) {
            return new ConversationSession(sessionId, "t", OffsetDateTime.now().toString(), OffsetDateTime.now().toString(), List.of());
        }

        @Override
        public List<jp.euks.myagent2.session.SessionSummary> listSessions() { return List.of(); }

        @Override
        public ConversationSession createNewSession() { return null; }

        @Override
        public void save(ConversationSession session) {
            if (session != null) saved.add(session.sessionId());
        }

        @Override
        public void deleteSession(String sessionId) { }
    }

    static class SimpleChatService implements ChatService {
        boolean cleared = false;
        @Override
        public void clearMemory() { cleared = true; }
        @Override
        public String replyTo(String userMessage) { return "ok"; }
    }

    static class BusyChatService extends SimpleChatService {
        CountDownLatch latch = new CountDownLatch(1);
        @Override
        public void streamReplyToWithHistory(java.util.List<ChatMessage> history, String userMessage,
                                            Consumer<String> onToken,
                                            Consumer<String> onComplete,
                                            Consumer<Throwable> onError,
                                            Consumer<String> onProgress) {
            new Thread(() -> {
                try {
                    onToken.accept("busy-start");
                    // 長めに待って busy 状態を維持
                    Thread.sleep(500);
                    onComplete.accept("done");
                } catch (Throwable t) {
                    onError.accept(t);
                } finally {
                    latch.countDown();
                }
            }).start();
        }
    }

    @Test
    public void evictionRespectsBusyAndCallsSaveAndClearOnEvicted() throws Exception {
        SpyConversationStore store = new SpyConversationStore();
        SessionRuntimeManager mgr = new SessionRuntimeManager(1, store, Path.of(System.getProperty("user.dir")));

        BusyChatService busySvc = new BusyChatService();
        ChatInteractor inter1 = new ChatInteractor(busySvc, new DefaultManualToolExecutor(), store, "s1");
        SessionRuntime rt1 = new SessionRuntime("s1", busySvc, inter1);
        mgr.putForTest(rt1);

        // ストリーミングを開始して busy にする
        CountDownLatch done = new CountDownLatch(1);
        inter1.startUserMessageStream("hi", t -> {}, p -> {}, () -> done.countDown(), e -> fail(e.toString()));
        // 確実に busy になっていることを待つ
        Thread.sleep(50);
        assertTrue(inter1.isBusy());

        // 新しいセッションを挿入すると、maxEntries=1 のため退避が発生するはず
        SimpleChatService svc2 = new SimpleChatService();
        ChatInteractor inter2 = new ChatInteractor(svc2, new DefaultManualToolExecutor(), store, "s2");
        SessionRuntime rt2 = new SessionRuntime("s2", svc2, inter2);
        mgr.putForTest(rt2);

        // 退避ルールにより busy な s1 は残り、s2 が退避（削除）されることを確認する
        assertTrue("busy session should remain", mgr.get("s1").isPresent());
        assertFalse("newly added non-busy session should be evicted", mgr.get("s2").isPresent());

        // evicted の interactor.save() が呼ばれているはず（store.saved に記録される）
        assertTrue("evicted session should have been saved", store.saved.contains("s2"));
        // evicted の chatService.clearMemory() が呼ばれているはず
        assertTrue("evicted chatService should have clearMemory called", svc2.cleared);

        // クリーンアップ: busy ストリームを待つ
        boolean completed = done.await(2, TimeUnit.SECONDS);
        assertTrue(completed);
        // busy セッションの clearMemory は呼ばれていない
        assertFalse("busy session should not have clearMemory called yet", busySvc.cleared);
    }
}
