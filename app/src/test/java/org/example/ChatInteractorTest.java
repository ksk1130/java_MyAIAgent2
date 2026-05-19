package org.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class ChatInteractorTest {
        @Test
        public void onUserMessageClearCommandClearsHistoryAndTranscript() {
            FakeConversationStore store = new FakeConversationStore();
            ChatInteractor interactor = new ChatInteractor(
                message -> "ok:" + message,
                message -> java.util.Optional.empty(),
                store);

            // まず履歴を2ターン分追加
            interactor.onUserMessage("hello");
            interactor.onUserMessage("world");
            assertTrue(interactor.getTranscript().contains("hello"));
            assertTrue(interactor.getTranscript().contains("world"));
            assertFalse(store.savedSessions.isEmpty());
            assertEquals(4, store.savedSessions.get(store.savedSessions.size() - 1).messages().size());

            // /clearコマンド実行
            String turn = interactor.onUserMessage("/clear");
            assertTrue(turn.contains("Assistant: 会話履歴を削除しました"));
            assertEquals("You: /clear\nAssistant: 会話履歴を削除しました\n\n", turn);
            assertEquals("", interactor.getTranscript());
            // 永続化も空
            assertEquals(0, store.savedSessions.get(store.savedSessions.size() - 1).messages().size());

            // /clear後に新規メッセージを送っても履歴が再開される
            String turn2 = interactor.onUserMessage("after clear");
            assertTrue(turn2.contains("after clear"));
            assertEquals(2, store.savedSessions.get(store.savedSessions.size() - 1).messages().size());
        }
    @Test
    public void onUserMessageReturnsEmptyWhenInputIsBlank() {
        ChatInteractor interactor = new ChatInteractor(message -> "echo: " + message);

        assertEquals("", interactor.onUserMessage("   "));
        assertEquals("", interactor.onUserMessage(null));
        assertEquals("", interactor.getTranscript());
    }

    @Test
    public void onUserMessageFormatsAndStoresTurnText() {
        ChatInteractor interactor = new ChatInteractor(message -> "echo: " + message);

        String turn = interactor.onUserMessage("  hello  ");

        assertEquals("You: hello\nAssistant: echo: hello\n\n", turn);
        assertEquals(turn, interactor.getTranscript());
    }

    @Test
    public void onUserMessageAccumulatesTranscript() {
        ChatInteractor interactor = new ChatInteractor(message -> "ok:" + message);

        interactor.onUserMessage("one");
        interactor.onUserMessage("two");

        assertEquals(
            "You: one\nAssistant: ok:one\n\n"
                + "You: two\nAssistant: ok:two\n\n",
            interactor.getTranscript());
    }

    @Test
    public void onUserMessageUsesManualToolWhenToolCommandIsGiven() {
        ChatInteractor interactor = new ChatInteractor(
            message -> "llm:" + message,
            message -> message.startsWith("/tool")
                ? java.util.Optional.of("(tool:test) 実行されました")
                : java.util.Optional.empty());

        String turn = interactor.onUserMessage("/tool test");

        assertTrue(turn.contains("Assistant: (tool:test) 実行されました"));
    }

    @Test
    public void onUserMessagePassesConversationHistoryToChatService() {
        List<List<ChatMessage>> historySnapshots = new ArrayList<>();

        ChatService service = new ChatService() {
            @Override
            public String replyTo(String userMessage) {
                return "unused:" + userMessage;
            }

            @Override
            public String replyToWithHistory(List<ChatMessage> history, String userMessage) {
                historySnapshots.add(List.copyOf(history));
                return "ok:" + userMessage;
            }
        };

        ChatInteractor interactor = new ChatInteractor(service, message -> java.util.Optional.empty());

        interactor.onUserMessage("first");
        interactor.onUserMessage("second");

        assertEquals(2, historySnapshots.size());
        assertEquals(0, historySnapshots.get(0).size());
        assertEquals(2, historySnapshots.get(1).size());
        assertEquals("user", historySnapshots.get(1).get(0).role());
        assertEquals("first", historySnapshots.get(1).get(0).content());
        assertEquals("assistant", historySnapshots.get(1).get(1).role());
        assertEquals("ok:first", historySnapshots.get(1).get(1).content());
    }

    @Test
    public void constructorLoadsTranscriptFromStore() {
        FakeConversationStore store = new FakeConversationStore();
        store.session = new ConversationSession(
            "s1",
            "title",
            "2026-05-03T00:00:00Z",
            "2026-05-03T00:00:00Z",
            List.of(
                new ChatMessage("user", "loaded user"),
                new ChatMessage("assistant", "loaded assistant")));

        ChatInteractor interactor = new ChatInteractor(
            message -> "echo:" + message,
            message -> java.util.Optional.empty(),
            store);

        assertTrue(interactor.getTranscript().contains("You: loaded user"));
        assertTrue(interactor.getTranscript().contains("Assistant: loaded assistant"));
    }

    @Test
    public void constructorRestoresLoadedSessionIntoChatServiceMemory() {
        FakeConversationStore store = new FakeConversationStore();
        store.session = new ConversationSession(
            "s1",
            "title",
            "2026-05-03T00:00:00Z",
            "2026-05-03T00:00:00Z",
            List.of(
                new ChatMessage("user", "loaded user"),
                new ChatMessage("assistant", "loaded assistant")));

        List<List<ChatMessage>> restoredSnapshots = new ArrayList<>();
        ChatService service = new ChatService() {
            @Override
            public String replyTo(String userMessage) {
                return "echo:" + userMessage;
            }

            @Override
            public void restoreMemory(List<ChatMessage> history) {
                restoredSnapshots.add(List.copyOf(history));
            }
        };

        new ChatInteractor(
            service,
            message -> java.util.Optional.empty(),
            store);

        assertEquals(1, restoredSnapshots.size());
        assertEquals(2, restoredSnapshots.get(0).size());
        assertEquals("loaded user", restoredSnapshots.get(0).get(0).content());
        assertEquals("loaded assistant", restoredSnapshots.get(0).get(1).content());
    }

    @Test
    public void onUserMessageSavesConversationWhenStoreIsEnabled() {
        FakeConversationStore store = new FakeConversationStore();
        ChatInteractor interactor = new ChatInteractor(
            message -> "ok:" + message,
            message -> java.util.Optional.empty(),
            store);

        interactor.onUserMessage("hello");

        assertFalse(store.savedSessions.isEmpty());
        ConversationSession saved = store.savedSessions.get(store.savedSessions.size() - 1);
        assertEquals(2, saved.messages().size());
        assertEquals("user", saved.messages().get(0).role());
        assertEquals("hello", saved.messages().get(0).content());
        assertEquals("assistant", saved.messages().get(1).role());
        assertEquals("ok:hello", saved.messages().get(1).content());
    }

    @Test
    public void onUserMessageSyncsWorkingDirectoryWhenSetdirSucceeded() {
        java.util.concurrent.atomic.AtomicReference<java.nio.file.Path> captured = new java.util.concurrent.atomic.AtomicReference<>();
        ChatService service = new ChatService() {
            @Override
            public String replyTo(String userMessage) {
                return "unused:" + userMessage;
            }

            @Override
            public void setWorkingDirectory(java.nio.file.Path dir) {
                captured.set(dir);
            }
        };

        ChatInteractor interactor = new ChatInteractor(
            service,
            message -> message.startsWith("/tool setdir")
                ? java.util.Optional.of("(tool:setdir) 作業ディレクトリを変更しました: C:\\work")
                : java.util.Optional.empty());

        interactor.onUserMessage("/tool setdir C:/Users/kskan/Desktop");

        assertEquals(java.nio.file.Path.of("C:/Users/kskan/Desktop"), captured.get());
    }

    @Test
    public void onUserMessageDoesNotSyncWorkingDirectoryWhenSetdirFailed() {
        java.util.concurrent.atomic.AtomicReference<java.nio.file.Path> captured = new java.util.concurrent.atomic.AtomicReference<>();
        ChatService service = new ChatService() {
            @Override
            public String replyTo(String userMessage) {
                return "unused:" + userMessage;
            }

            @Override
            public void setWorkingDirectory(java.nio.file.Path dir) {
                captured.set(dir);
            }
        };

        ChatInteractor interactor = new ChatInteractor(
            service,
            message -> message.startsWith("/tool setdir")
                ? java.util.Optional.of("(tool:error) 指定されたパスはディレクトリではありません")
                : java.util.Optional.empty());

        interactor.onUserMessage("/tool setdir C:/not/exist");

        assertEquals(null, captured.get());
    }

    private static final class FakeConversationStore implements ConversationStore {
        ConversationSession session = ConversationSession.createNew(java.time.Clock.systemUTC());
        List<ConversationSession> savedSessions = new ArrayList<>();

        @Override
        public ConversationSession loadLatestOrCreate() {
            return session;
        }

        @Override
        public ConversationSession loadByIdOrCreate(String sessionId) {
            return session;
        }

        @Override
        public List<SessionSummary> listSessions() {
            return List.of(new SessionSummary(session.sessionId(), session.title(), session.updatedAt()));
        }

        @Override
        public ConversationSession createNewSession() {
            session = ConversationSession.createNew(java.time.Clock.systemUTC());
            return session;
        }

        @Override
        public void save(ConversationSession session) {
            savedSessions.add(new ConversationSession(
                session.sessionId(),
                session.title(),
                session.createdAt(),
                session.updatedAt(),
                session.messages()));
            this.session = session;
        }

        @Override
        public void deleteSession(String sessionId) {
            if (this.session != null && this.session.sessionId().equals(sessionId)) {
                this.session = ConversationSession.createNew(java.time.Clock.systemUTC());
            }
        }
    }
}