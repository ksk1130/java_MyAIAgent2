package jp.euks.myagent2.proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jp.euks.myagent2.chat.ChatMessage;

import org.junit.Test;

public class GeminiOpenAiJsonProxyTest {

    @Test
    public void convertsOpenAiRequestToGeminiRequest() {
        String openAiRequest = GeminiOpenAiJsonProxy.toOpenAiRequestJson(
            "gemini-2.0-flash",
            List.of(new ChatMessage("assistant", "こんにちは")),
            "犬が好きです",
            "あなたは親切なアシスタントです");

        String geminiRequest = GeminiOpenAiJsonProxy.toGeminiRequestJson(openAiRequest, null);

        assertTrue(geminiRequest.contains("\"systemInstruction\""));
        assertTrue(geminiRequest.contains("\"contents\""));
        assertTrue(geminiRequest.contains("\"role\":\"model\""));
        assertTrue(geminiRequest.contains("\"role\":\"user\""));
    }

    @Test
    public void convertsGeminiResponseToOpenAiResponseAndExtractsAssistantText() {
        String geminiResponse = """
            {
              \"candidates\": [
                {
                  \"content\": {
                    \"parts\": [
                      { \"text\": \"犬が好きです\" }
                    ]
                  }
                }
              ]
            }
            """;

        String openAiResponse = GeminiOpenAiJsonProxy.toOpenAiResponseJson(geminiResponse, "gemini-2.0-flash", null);
        String assistantText = GeminiOpenAiJsonProxy.extractAssistantTextFromOpenAiResponse(openAiResponse);

        assertTrue(openAiResponse.contains("\"object\":\"chat.completion\""));
        assertTrue(openAiResponse.contains("\"role\":\"assistant\""));
        assertTrue("犬が好きです".equals(assistantText));
    }

    @Test
    public void convertsToolCallingBetweenOpenAiAndGemini() {
        String openAiRequest = """
            {
              "model": "gemini-2.0-flash",
              "messages": [
                {"role": "user", "content": "ファイル一覧を見せて"}
              ],
              "tools": [
                {
                  "type": "function",
                  "function": {
                    "name": "listFiles",
                    "description": "List files",
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "path": {"type": "string"}
                      },
                      "required": ["path"]
                    }
                  }
                }
              ]
            }
            """;

        String geminiRequest = GeminiOpenAiJsonProxy.toGeminiRequestJson(openAiRequest, null);
        assertTrue(geminiRequest.contains("\"functionDeclarations\""));
        assertTrue(geminiRequest.contains("\"name\":\"listFiles\""));

        String geminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "functionCall": {
                          "name": "listFiles",
                          "args": {"path": "."}
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """;

        String openAiResponse = GeminiOpenAiJsonProxy.toOpenAiResponseJson(geminiResponse, "gemini-2.0-flash", null);
        assertTrue(openAiResponse.contains("\"tool_calls\""));
        assertTrue(openAiResponse.contains("\"name\":\"listFiles\""));
        assertTrue(openAiResponse.contains("\"finish_reason\":\"tool_calls\""));
    }

    @Test
    public void returnsFallbackMessageWhenGeminiResponseHasNoTextAndIsBlocked() {
        String geminiResponse = """
            {
              "promptFeedback": {
                "blockReason": "SAFETY",
                "blockReasonMessage": "Harmful content"
              },
              "candidates": []
            }
            """;

        String openAiResponse = GeminiOpenAiJsonProxy.toOpenAiResponseJson(geminiResponse, "gemini-2.0-flash", null);
        String assistantText = GeminiOpenAiJsonProxy.extractAssistantTextFromOpenAiResponse(openAiResponse);

        assertTrue(assistantText.contains("ブロック"));
        assertTrue(assistantText.contains("SAFETY"));
    }

    /**
     * Gemini 応答に thought_signature が含まれる場合、ラウンドトリップで保持されること。
     * 具体的には:
     * 1. Gemini→OpenAI 変換でストアに part 全体が保存される
     * 2. OpenAI→Gemini 変換でストアを参照し thought_signature が再注入される
     */
    @Test
    public void thoughtSignatureIsPreservedInRoundTrip() {
        ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

        // Gemini が thought_signature 付き functionCall を含む応答を返した場合を模擬
        String geminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "thought": true,
                        "text": "Let me think..."
                      },
                      {
                        "functionCall": {
                          "name": "localcmd",
                          "args": {"command": "ls ."}
                        },
                        "thoughtSignature": "DUMMY_SIGNATURE_BASE64"
                      }
                    ]
                  }
                }
              ]
            }
            """;

        // Step 1: Gemini→OpenAI 変換（ストアに part 全体を保存）
        String openAiResponse = GeminiOpenAiJsonProxy.toOpenAiResponseJson(geminiResponse, "gemini-2.5-pro", store);
        assertTrue(openAiResponse.contains("\"tool_calls\""));
        assertTrue(openAiResponse.contains("\"name\":\"localcmd\""));

        // ストアに 1 件保存されていること
        assertTrue("metadata store should have 1 entry", store.size() == 1);
        String storedPartJson = store.values().iterator().next();
        assertTrue("stored part should contain thoughtSignature", storedPartJson.contains("thoughtSignature"));
        assertFalse("stored part should NOT be just functionCall object", storedPartJson.startsWith("{\"name\""));

        // thought:true のテキストはアシスタント応答に含まれないこと
        String assistantText = GeminiOpenAiJsonProxy.extractAssistantTextFromOpenAiResponse(openAiResponse);
        assertFalse("thought text should not appear in assistant text", assistantText.contains("Let me think..."));

        // Step 2: 次のターン — tool_call_id を取り出して OpenAI→Gemini 変換
        String toolCallId = extractFirstToolCallId(openAiResponse);
        String openAiRequest = """
            {
              "model": "gemini-2.5-pro",
              "messages": [
                {"role": "user", "content": "ls を実行して"},
                {
                  "role": "assistant",
                  "content": "",
                  "tool_calls": [
                    {
                      "id": "%s",
                      "type": "function",
                      "function": {"name": "localcmd", "arguments": "{\\"command\\":\\"ls \\"}"}
                    }
                  ]
                },
                {
                  "role": "tool",
                  "tool_call_id": "%s",
                  "content": "file1.txt\\nfile2.txt"
                }
              ]
            }
            """.formatted(toolCallId, toolCallId);

        String geminiRequest = GeminiOpenAiJsonProxy.toGeminiRequestJson(openAiRequest, store);

        // thought_signature が再注入されていること
        assertTrue("re-injected Gemini request should contain thoughtSignature",
                geminiRequest.contains("thoughtSignature"));
        assertTrue("re-injected Gemini request should contain functionCall",
                geminiRequest.contains("\"functionCall\""));
    }

    /** OpenAI 応答 JSON から最初の tool_call の id を取り出すヘルパー。 */
    private static String extractFirstToolCallId(String openAiResponseJson) {
        JsonObject root = JsonParser.parseString(openAiResponseJson).getAsJsonObject();
        return root.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .getAsJsonArray("tool_calls")
                .get(0).getAsJsonObject()
                .get("id").getAsString();
    }
}