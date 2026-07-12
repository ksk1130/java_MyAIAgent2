package jp.euks.myagent2.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jp.euks.myagent2.session.ConversationSession;
import jp.euks.myagent2.session.ConversationStore;
import jp.euks.myagent2.session.SessionSummary;
import jp.euks.myagent2.tools.BinaryAttachmentStore;
import jp.euks.myagent2.tools.ToolExecutionTracker;

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
    public void changeCurrentSessionTitlePersistsAndKeepsManualTitle() {
        FakeConversationStore store = new FakeConversationStore();
        ChatInteractor interactor = new ChatInteractor(
            message -> "ok:" + message,
            message -> java.util.Optional.empty(),
            store);

        interactor.changeCurrentSessionTitle("手動タイトル");
        interactor.onUserMessage("hello");

        assertFalse(store.savedSessions.isEmpty());
        ConversationSession saved = store.savedSessions.get(store.savedSessions.size() - 1);
        assertEquals("手動タイトル", saved.title());
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

    @Test
    public void onUserMessageExpandsAttachmentTokenOnlyForModelInput() throws Exception {
        Path tempDir = Files.createTempDirectory("interactor-attach-ok");
        Path binary = tempDir.resolve("sample.png");
        Files.write(binary, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        BinaryAttachmentStore store = new BinaryAttachmentStore(tempDir);
        String id = store.createAttachment("sample.png").id();

        FakeConversationStore conversationStore = new FakeConversationStore();
        conversationStore.session.setWorkingDirectory(tempDir.toString());
        java.util.concurrent.atomic.AtomicReference<String> modelInput = new java.util.concurrent.atomic.AtomicReference<>("");

        ChatService service = new ChatService() {
            @Override
            public String replyTo(String userMessage) {
                return "unused";
            }

            @Override
            public String replyToWithHistory(List<ChatMessage> history, String userMessage) {
                modelInput.set(userMessage);
                return "ok";
            }

            @Override
            public Path getWorkingDirectory() {
                return tempDir;
            }
        };

        ChatInteractor interactor = new ChatInteractor(service, message -> java.util.Optional.empty(), conversationStore, null, store);

        String raw = "確認してください [[ATTACH:" + id + "]]";
        interactor.onUserMessage(raw);

        assertTrue(modelInput.get(), modelInput.get().contains("\"format\":\"openai_chat_completions_multimodal\""));
        assertTrue(modelInput.get(), modelInput.get().contains("\"type\":\"image_url\""));
        assertTrue(modelInput.get(), modelInput.get().contains("\"url\":\"data:image/png;base64,"));
        ConversationSession saved = conversationStore.savedSessions.get(conversationStore.savedSessions.size() - 1);
        assertEquals(raw, saved.messages().get(0).content());
        assertTrue(interactor.getTranscript().contains(raw));
    }

    @Test
    public void onUserMessageExpandsAttachmentTokenAsGeminiMultimodalJson() throws Exception {
        Path tempDir = Files.createTempDirectory("interactor-attach-gemini");
        Path binary = tempDir.resolve("sample.png");
        Files.write(binary, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        BinaryAttachmentStore store = new BinaryAttachmentStore(tempDir);
        String id = store.createAttachment("sample.png").id();

        FakeConversationStore conversationStore = new FakeConversationStore();
        conversationStore.session.setWorkingDirectory(tempDir.toString());
        conversationStore.session.setProvider("gemini");
        java.util.concurrent.atomic.AtomicReference<String> modelInput = new java.util.concurrent.atomic.AtomicReference<>("");

        ChatService service = new ChatService() {
            @Override
            public String replyTo(String userMessage) {
                return "unused";
            }

            @Override
            public String replyToWithHistory(List<ChatMessage> history, String userMessage) {
                modelInput.set(userMessage);
                return "ok";
            }

            @Override
            public Path getWorkingDirectory() {
                return tempDir;
            }
        };

        ChatInteractor interactor = new ChatInteractor(service, message -> java.util.Optional.empty(), conversationStore, null, store);
        interactor.onUserMessage("画像確認 [[ATTACH:" + id + "]]");

        assertTrue(modelInput.get(), modelInput.get().contains("\"format\":\"gemini_generate_content_multimodal\""));
        assertTrue(modelInput.get(), modelInput.get().contains("\"inline_data\""));
        assertTrue(modelInput.get(), modelInput.get().contains("\"mime_type\":\"image/png\""));
    }

    @Test
    public void onUserMessageReturnsErrorForUnknownAttachmentToken() throws Exception {
        Path tempDir = Files.createTempDirectory("interactor-attach-ng");
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();

        ChatService service = new ChatService() {
            @Override
            public String replyTo(String userMessage) {
                return "unused";
            }

            @Override
            public String replyToWithHistory(List<ChatMessage> history, String userMessage) {
                callCount.incrementAndGet();
                return "ok";
            }

            @Override
            public Path getWorkingDirectory() {
                return tempDir;
            }
        };

        ChatInteractor interactor = new ChatInteractor(service, message -> java.util.Optional.empty());
        String token = "[[ATTACH:11111111-1111-1111-1111-111111111111]]";

        String turn = interactor.onUserMessage("添付 " + token);

        assertTrue(turn, turn.contains("attachmentId が無効です"));
        assertEquals(0, callCount.get());
    }

    @Test
    public void onUserMessageRetriesWhenAssistantReturnsAttachmentToken() {
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> secondPrompt = new java.util.concurrent.atomic.AtomicReference<>("");

        ChatService service = new ChatService() {
            @Override
            public String replyTo(String userMessage) {
                return "unused";
            }

            @Override
            public String replyToWithHistory(List<ChatMessage> history, String userMessage) {
                int n = callCount.incrementAndGet();
                if (n == 1) {
                    return "[[ATTACH:d1f2acb2-10c6-4937-967b-9a042cac3555]]";
                }
                secondPrompt.set(userMessage);
                return "要約結果: 売上は先月比で増加しています。";
            }
        };

        ChatInteractor interactor = new ChatInteractor(service, message -> java.util.Optional.empty());
        String turn = interactor.onUserMessage("Test.xlsxを要約して");

        assertEquals(2, callCount.get());
        assertTrue(secondPrompt.get(), secondPrompt.get().contains("回答には [[ATTACH:...]] のようなトークンを含めず"));
        assertTrue(turn, turn.contains("要約結果: 売上は先月比で増加しています。"));
        assertFalse(turn, turn.contains("[[ATTACH:"));
    }

    @Test
    public void onUserMessageRetriesWhenAssistantReturnsMalformedAttachmentToken() {
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();

        ChatService service = new ChatService() {
            @Override
            public String replyTo(String userMessage) {
                return "unused";
            }

            @Override
            public String replyToWithHistory(List<ChatMessage> history, String userMessage) {
                int n = callCount.incrementAndGet();
                if (n == 1) {
                    return "[[ATTACH:e5e074b5-5653-4630-9df0-e3adc744b5-163]]";
                }
                return "要約結果: 主要な項目はすべて確認できました。";
            }
        };

        ChatInteractor interactor = new ChatInteractor(service, message -> java.util.Optional.empty());
        String turn = interactor.onUserMessage("Test.xlsxを要約して");

        assertEquals(2, callCount.get());
        assertTrue(turn, turn.contains("要約結果: 主要な項目はすべて確認できました。"));
        assertFalse(turn, turn.contains("[[ATTACH:"));
    }

    @Test
    public void onUserMessageRetriesSummaryWhenReadbinaryOutputOnly() {
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        ToolExecutionTracker tracker = new ToolExecutionTracker();
        tracker.record(
            "readbinary",
            "Test.xlsx",
            "file=Test.xlsx mime=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet size=87761 base64=AAAA");

        ChatService service = new ChatService() {
            @Override
            public String replyTo(String userMessage) {
                return "unused";
            }

            @Override
            public String replyToWithHistory(List<ChatMessage> history, String userMessage) {
                int n = callCount.incrementAndGet();
                if (n == 1) {
                    return "file=Test.xlsx mime=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet size=87761 base64=AAAA";
                }
                return "要約結果: 売上データは月次で増加傾向です。";
            }

            @Override
            public ToolExecutionTracker getToolExecutionTracker() {
                return tracker;
            }
        };

        ChatInteractor interactor = new ChatInteractor(service, message -> java.util.Optional.empty());
        String turn = interactor.onUserMessage("Test.xlsxを要約して");

        assertEquals(2, callCount.get());
        assertTrue(turn, turn.contains("要約結果: 売上データは月次で増加傾向です。"));
    }

    @Test
    public void binaryAttachmentStoreDoesNotCreateMyagent2Directory() throws Exception {
        Path tempDir = Files.createTempDirectory("attach-memory-only");
        Path sample = tempDir.resolve("test.png");
        Files.write(sample, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});

        // 添付ストアを作成してファイルを登録
        BinaryAttachmentStore store = new BinaryAttachmentStore(tempDir);
        String id = store.createAttachment("test.png").id();

        // .myagent2 ディレクトリが作成されていないことを確認
        Path myagent2 = tempDir.resolve(".myagent2");
        assertFalse(".myagent2 directory should NOT be created in memory-only mode", Files.exists(myagent2));

        // 登録されたメタデータはメモリに保持され、アクセス可能
        assertTrue("Attachment metadata should be accessible", store.getMeta(id).isPresent());
        assertTrue("Base64 should be retrievable", store.getBase64(id).isPresent());
        assertTrue("File should exist", store.exists(id));
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