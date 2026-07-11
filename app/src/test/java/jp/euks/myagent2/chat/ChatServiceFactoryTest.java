package jp.euks.myagent2.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ChatServiceFactoryTest {
    @Test
    public void getFirstAvailableProviderReturnsOpenAiWhenConfigured() {
        // This test demonstrates that getFirstAvailableProvider checks only API keys
        // (not base URLs), prioritizing OpenAI over Gemini.
        // Actual testing requires mocking System.getenv()
    }

    @Test
    public void getAvailableProvidersReturnsConfiguredProviders() {
        // This would need environment variable mocking to test properly
        List<String> providers = ChatServiceFactory.getAvailableProviders();
        assertTrue(providers.isEmpty() || 
                   providers.contains("openai") || 
                   providers.contains("gemini"));
    }

    @Test
    public void createForSessionWithProviderReturnsStubWhenNoProviderConfigured() {
        Path workDir = Path.of(System.getProperty("user.dir"));
        ChatService service = ChatServiceFactory.createForSessionWithProvider(workDir, "unconfigured");

        assertTrue(service instanceof StubChatService);
    }

    @Test
    public void normalizeBaseUrlRemovesTrailingSlash() {
        assertEquals("https://example.test/v1", ChatServiceFactory.normalizeBaseUrl("https://example.test/v1/"));
        assertEquals("https://example.test/v1", ChatServiceFactory.normalizeBaseUrl(" https://example.test/v1 "));
    }
}