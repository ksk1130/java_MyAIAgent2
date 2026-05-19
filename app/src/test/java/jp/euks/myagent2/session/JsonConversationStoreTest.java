package jp.euks.myagent2.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import jp.euks.myagent2.chat.ChatMessage;

import org.junit.Test;

public class JsonConversationStoreTest {
    @Test
    public void loadLatestOrCreateCreatesSessionWhenNoFileExists() throws Exception {
        Path tempDir = Files.createTempDirectory("myagent2-store-test");
        JsonConversationStore store = new JsonConversationStore(tempDir, fixedClock());

        ConversationSession session = store.loadLatestOrCreate();

        assertFalse(session.sessionId().isBlank());
        assertTrue(session.messages().isEmpty());
        assertTrue(Files.exists(tempDir.resolve(".myagent2/sessions/index.json")));
    }

    @Test
    public void saveAndLoadLatestRestoresMessages() throws Exception {
        Path tempDir = Files.createTempDirectory("myagent2-store-test");
        JsonConversationStore store = new JsonConversationStore(tempDir, fixedClock());

        ConversationSession created = store.loadLatestOrCreate();
        created.replaceMessages(List.of(
            new ChatMessage("user", "hello"),
            new ChatMessage("assistant", "world")));
        store.save(created);

        ConversationSession loaded = store.loadLatestOrCreate();
        assertEquals(created.sessionId(), loaded.sessionId());
        assertEquals(2, loaded.messages().size());
        assertEquals("hello", loaded.messages().get(0).content());
        assertEquals("world", loaded.messages().get(1).content());
        assertEquals("hello", loaded.title());
    }

    @Test
    public void deleteSessionRemovesSessionFileAndSummary() throws Exception {
        Path tempDir = Files.createTempDirectory("myagent2-store-test");
        JsonConversationStore store = new JsonConversationStore(tempDir, fixedClock());

        ConversationSession created = store.createNewSession();
        String sessionId = created.sessionId();
        Path sessionPath = tempDir.resolve(".myagent2/sessions").resolve(sessionId + ".json");

        assertTrue(Files.exists(sessionPath));
        assertFalse(store.listSessions().isEmpty());

        store.deleteSession(sessionId);

        assertFalse(Files.exists(sessionPath));
        assertTrue(store.listSessions().stream().noneMatch(s -> sessionId.equals(s.sessionId())));
    }

    @Test
    public void deleteLatestSessionUpdatesLatestToRemainingSession() throws Exception {
        Path tempDir = Files.createTempDirectory("myagent2-store-test");
        JsonConversationStore store = new JsonConversationStore(tempDir, fixedClock());

        ConversationSession first = store.createNewSession();
        ConversationSession second = store.createNewSession();

        store.deleteSession(second.sessionId());

        ConversationSession loaded = store.loadLatestOrCreate();
        assertEquals(first.sessionId(), loaded.sessionId());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-05-03T00:00:00Z"), ZoneOffset.UTC);
    }
}
