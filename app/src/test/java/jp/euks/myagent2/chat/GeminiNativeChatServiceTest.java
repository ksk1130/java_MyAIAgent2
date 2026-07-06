package jp.euks.myagent2.chat;

import static org.junit.Assert.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

/**
 * GeminiNativeChatService の単体テスト。
 * LangChain4j ベースの実装を検証する。
 */
public class GeminiNativeChatServiceTest {

    @Test
    public void testGeminiNativeChatServiceCreation() {
        ChatService service = new GeminiNativeChatService("dummy-key", "gemini-2.0-flash");
        assertNotNull(service);
        assertNotNull(service.getSystemPrompt());
        assertTrue(service.getSystemPrompt().length() > 0);
    }

    @Test
    public void testSetAndGetSystemPrompt() {
        GeminiNativeChatService service = new GeminiNativeChatService("dummy-key", "gemini-2.0-flash");
        String customPrompt = "カスタムプロンプト";
        service.setSystemPrompt(customPrompt);
        assertEquals(customPrompt, service.getSystemPrompt());
    }

    @Test
    public void testSetSystemPromptWithNullResetsToDefault() {
        GeminiNativeChatService service = new GeminiNativeChatService("dummy-key", "gemini-2.0-flash");
        String original = service.getSystemPrompt();
        service.setSystemPrompt("temporary");
        service.setSystemPrompt(null);
        assertEquals(original, service.getSystemPrompt());
    }

    @Test
    public void testSetAndGetWorkingDirectory() {
        GeminiNativeChatService service = new GeminiNativeChatService("dummy-key", "gemini-2.0-flash");
        Path testDir = Path.of("/tmp/test");
        service.setWorkingDirectory(testDir);
        assertEquals(testDir, service.getWorkingDirectory());
    }

    @Test
    public void testSetWorkingDirectoryWithNullIgnored() {
        GeminiNativeChatService service = new GeminiNativeChatService("dummy-key", "gemini-2.0-flash");
        Path original = service.getWorkingDirectory();
        service.setWorkingDirectory(null);
        assertEquals(original, service.getWorkingDirectory());
    }

    @Test
    public void testReplyToWithEmptyHistoryReturnsError() {
        GeminiNativeChatService service = new GeminiNativeChatService("invalid-key", "gemini-2.0-flash");
        String response = service.replyToWithHistory(List.of(), "test message");
        assertTrue(response.contains("(error)"));
    }

    @Test
    public void testChatMessageWithHistoryCanBeProcessed() {
        GeminiNativeChatService service = new GeminiNativeChatService("invalid-key", "gemini-2.0-flash");
        List<ChatMessage> history = List.of();
        String response = service.replyToWithHistory(history, "test message");
        assertNotNull(response);
    }

    @Test
    public void testGetToolExecutionTracker() {
        GeminiNativeChatService service = new GeminiNativeChatService("dummy-key", "gemini-2.0-flash");
        assertNotNull(service.getToolExecutionTracker());
    }

    @Test
    public void testClearMemory() {
        GeminiNativeChatService service = new GeminiNativeChatService("dummy-key", "gemini-2.0-flash");
        service.clearMemory();
        // No exception
    }

    @Test
    public void testRestoreMemory() {
        GeminiNativeChatService service = new GeminiNativeChatService("dummy-key", "gemini-2.0-flash");
        service.restoreMemory(List.of());
        // No exception
    }
}
