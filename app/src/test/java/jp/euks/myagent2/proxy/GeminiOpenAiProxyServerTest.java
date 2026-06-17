package jp.euks.myagent2.proxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GeminiOpenAiProxyServerTest {

    @Test
    public void resolveGeminiGenerateContentUrlConvertsStreamEndpointToGenerateEndpoint() {
        String baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:streamGenerateContent";

        String resolved = GeminiOpenAiProxyServer.resolveGeminiGenerateContentUrl(baseUrl, "gemini-2.0-flash");

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent",
            resolved);
    }

    @Test
    public void resolveGeminiGenerateContentUrlKeepsGenerateEndpointAsIs() {
        String baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

        String resolved = GeminiOpenAiProxyServer.resolveGeminiGenerateContentUrl(baseUrl, "gemini-2.0-flash");

        assertEquals(baseUrl, resolved);
    }

    @Test
    public void resolveGeminiGenerateContentUrlBuildsEndpointFromBaseUrlAndModel() {
        String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

        String resolved = GeminiOpenAiProxyServer.resolveGeminiGenerateContentUrl(baseUrl, "gemini-2.0-flash");

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            resolved);
    }
}
