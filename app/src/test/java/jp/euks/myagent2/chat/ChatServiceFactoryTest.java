package jp.euks.myagent2.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ChatServiceFactoryTest {
    @Test
    public void createFromEnvReturnsStubWhenApiKeyMissing() {
        ChatService service = ChatServiceFactory.createFromEnv(Map.of());

        assertTrue(service instanceof StubChatService);
    }

    @Test
    public void createFromEnvReturnsOpenAiServiceWhenApiKeyExists() {
        Map<String, String> env = new HashMap<>();
        env.put(ChatServiceFactory.ENV_API_KEY, "test-key");

        ChatService service = ChatServiceFactory.createFromEnv(env);

        assertTrue(service instanceof OpenAiCompatibleChatService);
    }

    @Test
    public void createFromEnvReturnsOpenAiServiceViaGeminiProxyWhenBaseUrlContainsGoogle() {
        Map<String, String> env = new HashMap<>();
        env.put(ChatServiceFactory.ENV_API_KEY, "test-key");
        env.put(ChatServiceFactory.ENV_BASE_URL, "https://generativelanguage.googleapis.com/v1beta");

        ChatService service = ChatServiceFactory.createFromEnv(env);

        assertTrue(service instanceof OpenAiCompatibleChatService);
    }

    @Test
    public void normalizeBaseUrlRemovesTrailingSlash() {
        assertEquals("https://example.test/v1", ChatServiceFactory.normalizeBaseUrl("https://example.test/v1/"));
        assertEquals("https://example.test/v1", ChatServiceFactory.normalizeBaseUrl(" https://example.test/v1 "));
    }

    @Test
    public void containsGoogleHostDetectsGoogleUrl() {
        assertTrue(ChatServiceFactory.containsGoogleHost("https://generativelanguage.googleapis.com/v1beta"));
    }
}