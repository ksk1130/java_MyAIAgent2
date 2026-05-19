package org.example;

import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class GeminiOpenAiJsonProxyTest {

    @Test
    public void convertsOpenAiRequestToGeminiRequest() {
        String openAiRequest = GeminiOpenAiJsonProxy.toOpenAiRequestJson(
            "gemini-2.0-flash",
            List.of(new ChatMessage("assistant", "こんにちは")),
            "犬が好きです",
            "あなたは親切なアシスタントです");

        String geminiRequest = GeminiOpenAiJsonProxy.toGeminiRequestJson(openAiRequest);

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

        String openAiResponse = GeminiOpenAiJsonProxy.toOpenAiResponseJson(geminiResponse, "gemini-2.0-flash");
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

        String geminiRequest = GeminiOpenAiJsonProxy.toGeminiRequestJson(openAiRequest);
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

        String openAiResponse = GeminiOpenAiJsonProxy.toOpenAiResponseJson(geminiResponse, "gemini-2.0-flash");
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

        String openAiResponse = GeminiOpenAiJsonProxy.toOpenAiResponseJson(geminiResponse, "gemini-2.0-flash");
        String assistantText = GeminiOpenAiJsonProxy.extractAssistantTextFromOpenAiResponse(openAiResponse);

        assertTrue(assistantText.contains("ブロック"));
        assertTrue(assistantText.contains("SAFETY"));
    }
}