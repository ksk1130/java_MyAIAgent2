package jp.euks.myagent2.proxy;



import java.util.Objects;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jp.euks.myagent2.chat.ChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 形式 JSON と Gemini 形式 JSON の相互変換を行うユーティリティ。
 */
public final class GeminiOpenAiJsonProxy {
    private static final Gson GSON = new Gson();

    /**
     * 会話履歴を OpenAI Chat Completions の入力 JSON に変換する。
     */
    private GeminiOpenAiJsonProxy() {
    }

    /**
     * OpenAI Chat Completions 用のリクエスト JSON を生成します。
     *
     * @param model        使用するモデル名
     * @param history      会話履歴（ChatMessage のリスト）
     * @param userMessage  現在のユーザーメッセージ
     * @param systemPrompt システムプロンプト（null 可）
     * @return OpenAI 互換の JSON 文字列
     */
    public static String toOpenAiRequestJson(
            String model,
            List<ChatMessage> history,
            String userMessage,
            String systemPrompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);

        JsonArray messages = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", systemPrompt);
            messages.add(system);
        }

        if (history != null) {
            for (ChatMessage message : history) {
                if (Objects.isNull(message) || Objects.isNull(message.role())) {
                    continue;
                }
                if (!isOpenAiRole(message.role())) {
                    continue;
                }
                JsonObject msg = new JsonObject();
                msg.addProperty("role", message.role());
                msg.addProperty("content", nullSafe(message.content()));
                messages.add(msg);
            }
        }

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", nullSafe(userMessage));
        messages.add(user);

        root.add("messages", messages);
        root.addProperty("temperature", 0.2);
        return GSON.toJson(root);
    }

    /**
     * OpenAI 形式リクエスト JSON を Gemini generateContent 形式へ変換する。
     */
    public static String toGeminiRequestJson(String openAiRequestJson) {
        JsonObject openAiRoot = JsonParser.parseString(openAiRequestJson).getAsJsonObject();
        JsonObject geminiRoot = new JsonObject();

        JsonArray contents = new JsonArray();
        List<String> systemTexts = new ArrayList<>();
        Map<String, String> toolCallIdToName = new HashMap<>();

        JsonArray messages = openAiRoot.has("messages") && openAiRoot.get("messages").isJsonArray()
                ? openAiRoot.getAsJsonArray("messages")
                : new JsonArray();

        for (JsonElement element : messages) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject message = element.getAsJsonObject();
            String role = message.has("role") ? message.get("role").getAsString() : "";
            String content = readStringContent(message);
            jp.euks.myagent2.chat.ChatRole r = jp.euks.myagent2.chat.ChatRole.parse(role);

            if (r == jp.euks.myagent2.chat.ChatRole.SYSTEM) {
                if (!content.isBlank()) {
                    systemTexts.add(content);
                }
                continue;
            }
            if (r == jp.euks.myagent2.chat.ChatRole.USER || r == jp.euks.myagent2.chat.ChatRole.ASSISTANT) {
                JsonArray parts = new JsonArray();
                if (!content.isBlank()) {
                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("text", content);
                    parts.add(textPart);
                }
                if (r == jp.euks.myagent2.chat.ChatRole.ASSISTANT && message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
                    JsonArray toolCalls = message.getAsJsonArray("tool_calls");
                    for (JsonElement toolCallElement : toolCalls) {
                        if (!toolCallElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject toolCall = toolCallElement.getAsJsonObject();
                        if (!toolCall.has("function") || !toolCall.get("function").isJsonObject()) {
                            continue;
                        }
                        JsonObject function = toolCall.getAsJsonObject("function");
                        String toolName = function.has("name") ? function.get("name").getAsString() : "";
                        if (toolName.isBlank()) {
                            continue;
                        }
                        String toolCallId = toolCall.has("id") ? toolCall.get("id").getAsString() : "";
                        if (!toolCallId.isBlank()) {
                            toolCallIdToName.put(toolCallId, toolName);
                        }
                        JsonObject functionCallPart = new JsonObject();
                        JsonObject functionCall = new JsonObject();
                        functionCall.addProperty("name", toolName);
                        functionCall.add("args",
                                parseJsonObjectOrEmpty(function.has("arguments") ? function.get("arguments") : null));
                        functionCallPart.add("functionCall", functionCall);
                        parts.add(functionCallPart);
                    }
                }

                if (parts.size() == 0) {
                    continue;
                }

                JsonObject contentObject = new JsonObject();
                contentObject.addProperty("role", r == jp.euks.myagent2.chat.ChatRole.ASSISTANT ? "model" : "user");
                contentObject.add("parts", parts);
                contents.add(contentObject);
                continue;
            }

            if (r == jp.euks.myagent2.chat.ChatRole.TOOL) {
                String toolCallId = message.has("tool_call_id") ? message.get("tool_call_id").getAsString() : "";
                String toolName = toolCallIdToName.getOrDefault(toolCallId, "tool");
                JsonObject contentObject = new JsonObject();
                contentObject.addProperty("role", "user");
                JsonArray parts = new JsonArray();
                JsonObject functionResponsePart = new JsonObject();
                JsonObject functionResponse = new JsonObject();
                functionResponse.addProperty("name", toolName);
                JsonObject response = new JsonObject();
                response.addProperty("content", content);
                functionResponse.add("response", response);
                functionResponsePart.add("functionResponse", functionResponse);
                parts.add(functionResponsePart);
                contentObject.add("parts", parts);
                contents.add(contentObject);
            }
        }

        if (!systemTexts.isEmpty()) {
            JsonObject systemInstruction = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", String.join("\n\n", systemTexts));
            parts.add(textPart);
            systemInstruction.add("parts", parts);
            geminiRoot.add("systemInstruction", systemInstruction);
        }

        geminiRoot.add("contents", contents);

        JsonArray geminiTools = convertOpenAiToolsToGeminiTools(openAiRoot);
        if (!geminiTools.isEmpty()) {
            geminiRoot.add("tools", geminiTools);
        }

        if (openAiRoot.has("temperature") && !openAiRoot.get("temperature").isJsonNull()) {
            JsonObject generationConfig = new JsonObject();
            generationConfig.add("temperature", openAiRoot.get("temperature"));
            geminiRoot.add("generationConfig", generationConfig);
        }

        return GSON.toJson(geminiRoot);
    }

    /**
     * Gemini レスポンスを OpenAI Chat Completions 互換のレスポンス JSON に変換します。
     *
     * @param geminiResponseJson Gemini 形式のレスポンス JSON
     * @param model              モデル名
     * @return OpenAI 互換のレスポンス JSON
     */

    public static String toOpenAiResponseJson(String geminiResponseJson, String model) {
        JsonObject geminiRoot = JsonParser.parseString(geminiResponseJson).getAsJsonObject();

        ResponseParts responseParts = extractResponsePartsFromGemini(geminiRoot);
        String finalText = responseParts.text;
        if (finalText.isBlank() && responseParts.toolCalls.isEmpty() && !responseParts.fallbackMessage.isBlank()) {
            finalText = responseParts.fallbackMessage;
        }

        JsonObject root = new JsonObject();
        root.addProperty("id", "chatcmpl-proxy-" + UUID.randomUUID());
        root.addProperty("object", "chat.completion");
        root.addProperty("created", Instant.now().getEpochSecond());
        root.addProperty("model", model);

        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        // null ではなく空文字を返すことで、クライアント実装差異による取りこぼしを防ぐ
        message.addProperty("content", finalText);
        if (!responseParts.toolCalls.isEmpty()) {
            message.add("tool_calls", responseParts.toolCalls);
        }
        choice.add("message", message);
        choice.addProperty("finish_reason", responseParts.toolCalls.isEmpty() ? "stop" : "tool_calls");
        choices.add(choice);
        root.add("choices", choices);

        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 0);
        usage.addProperty("completion_tokens", 0);
        usage.addProperty("total_tokens", 0);
        root.add("usage", usage);

        return GSON.toJson(root);
    }

    /**
     * Gemini レスポンス JSON の簡易サマリを返します（候補数・関数呼び出し数等）。
     *
     * @param geminiResponseJson Gemini レスポンス JSON
     * @return 要約文字列
     */
    public static String summarizeGeminiResponse(String geminiResponseJson) {
        try {
            JsonObject root = JsonParser.parseString(geminiResponseJson).getAsJsonObject();
            int candidateCount = 0;
            String finishReason = "";
            int textChars = 0;
            int functionCallCount = 0;

            if (root.has("candidates") && root.get("candidates").isJsonArray()) {
                JsonArray candidates = root.getAsJsonArray("candidates");
                candidateCount = candidates.size();
                if (!candidates.isEmpty() && candidates.get(0).isJsonObject()) {
                    JsonObject first = candidates.get(0).getAsJsonObject();
                    finishReason = first.has("finishReason") && !first.get("finishReason").isJsonNull()
                            ? first.get("finishReason").getAsString()
                            : "";
                    if (first.has("content") && first.get("content").isJsonObject()) {
                        JsonObject content = first.getAsJsonObject("content");
                        if (content.has("parts") && content.get("parts").isJsonArray()) {
                            for (JsonElement element : content.getAsJsonArray("parts")) {
                                if (!element.isJsonObject()) {
                                    continue;
                                }
                                JsonObject part = element.getAsJsonObject();
                                if (part.has("text") && !part.get("text").isJsonNull()) {
                                    textChars += part.get("text").getAsString().length();
                                }
                                if (part.has("functionCall") && part.get("functionCall").isJsonObject()) {
                                    functionCallCount++;
                                }
                            }
                        }
                    }
                }
            }

            String blockReason = "";
            if (root.has("promptFeedback") && root.get("promptFeedback").isJsonObject()) {
                JsonObject feedback = root.getAsJsonObject("promptFeedback");
                if (feedback.has("blockReason") && !feedback.get("blockReason").isJsonNull()) {
                    blockReason = feedback.get("blockReason").getAsString();
                }
            }

            return "candidates=%s, finishReason=".formatted(candidateCount) + (finishReason.isBlank() ? "(none)" : finishReason)
                    + ", textChars=%s, functionCalls=".formatted(textChars) + functionCallCount
                    + ", blockReason=" + (blockReason.isBlank() ? "(none)" : blockReason);
        } catch (RuntimeException e) {
            return "summary-parse-error=" + e.getClass().getSimpleName();
        }
    }

    /**
     * OpenAI 形式レスポンスを診断しやすい要約文字列を返す。
     */
    public static String summarizeOpenAiResponse(String openAiResponseJson) {
        try {
            JsonObject root = JsonParser.parseString(openAiResponseJson).getAsJsonObject();
            int choicesCount = root.has("choices") && root.get("choices").isJsonArray()
                    ? root.getAsJsonArray("choices").size()
                    : 0;

            int contentChars = 0;
            int toolCalls = 0;
            String finishReason = "";

            if (choicesCount > 0) {
                JsonObject firstChoice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
                finishReason = firstChoice.has("finish_reason") && !firstChoice.get("finish_reason").isJsonNull()
                        ? firstChoice.get("finish_reason").getAsString()
                        : "";
                if (firstChoice.has("message") && firstChoice.get("message").isJsonObject()) {
                    JsonObject message = firstChoice.getAsJsonObject("message");
                    if (message.has("content") && !message.get("content").isJsonNull()) {
                        contentChars = message.get("content").getAsString().length();
                    }
                    if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
                        toolCalls = message.getAsJsonArray("tool_calls").size();
                    }
                }
            }

            return "choices=%s, finish_reason=".formatted(choicesCount) + (finishReason.isBlank() ? "(none)" : finishReason)
                    + ", contentChars=%s, toolCalls=".formatted(contentChars) + toolCalls;
        } catch (RuntimeException e) {
            return "summary-parse-error=" + e.getClass().getSimpleName();
        }
    }

    /**
     * OpenAI 形式レスポンス JSON の簡易サマリを返します。
     *
     * @param openAiResponseJson OpenAI 形式のレスポンス JSON
     * @return 要約文字列
     */
    public static String extractAssistantTextFromOpenAiResponse(String openAiResponseJson) {
        JsonObject root = JsonParser.parseString(openAiResponseJson).getAsJsonObject();
        if (!root.has("choices") || !root.get("choices").isJsonArray()) {
            return "";
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return "";
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        if (!choice.has("message") || !choice.get("message").isJsonObject()) {
            return "";
        }
        JsonObject message = choice.getAsJsonObject("message");
        return message.has("content") ? nullSafe(message.get("content").getAsString()) : "";
    }

    /**
     * Gemini レスポンス JSON からテキスト・ツール呼び出し情報・フォールバックメッセージを抽出します。
     *
     * @param geminiRoot 解析対象の Gemini JSON オブジェクト
     * @return 抽出結果を格納した ResponseParts
     */
    private static ResponseParts extractResponsePartsFromGemini(JsonObject geminiRoot) {
        ResponseParts result = new ResponseParts();

        if (geminiRoot.has("promptFeedback") && geminiRoot.get("promptFeedback").isJsonObject()) {
            JsonObject promptFeedback = geminiRoot.getAsJsonObject("promptFeedback");
            String blockReason = promptFeedback.has("blockReason") && !promptFeedback.get("blockReason").isJsonNull()
                    ? promptFeedback.get("blockReason").getAsString()
                    : "";
            String blockMessage = promptFeedback.has("blockReasonMessage")
                    && !promptFeedback.get("blockReasonMessage").isJsonNull()
                            ? promptFeedback.get("blockReasonMessage").getAsString()
                            : "";
            if (!blockReason.isBlank() || !blockMessage.isBlank()) {
                result.fallbackMessage = "(gemini) 応答がブロックされました: "
                        + (blockReason.isBlank() ? "UNKNOWN" : blockReason)
                        + (blockMessage.isBlank() ? "" : " - " + blockMessage);
            }
        }

        if (!geminiRoot.has("candidates") || !geminiRoot.get("candidates").isJsonArray()) {
            return result;
        }

        JsonArray candidates = geminiRoot.getAsJsonArray("candidates");
        if (candidates.isEmpty() || !candidates.get(0).isJsonObject()) {
            return result;
        }

        JsonObject first = candidates.get(0).getAsJsonObject();
        String finishReason = first.has("finishReason") && !first.get("finishReason").isJsonNull()
                ? first.get("finishReason").getAsString()
                : "";
        if (!finishReason.isBlank() && !"STOP".equals(finishReason)) {
            result.fallbackMessage = "(gemini) 生成が完了しませんでした: " + finishReason;
        }

        if (!first.has("content") || !first.get("content").isJsonObject()) {
            return result;
        }

        JsonObject content = first.getAsJsonObject("content");
        if (!content.has("parts") || !content.get("parts").isJsonArray()) {
            return result;
        }

        StringBuilder text = new StringBuilder();
        for (JsonElement partElement : content.getAsJsonArray("parts")) {
            if (!partElement.isJsonObject()) {
                continue;
            }
            JsonObject part = partElement.getAsJsonObject();
            if (part.has("text")) {
                text.append(part.get("text").getAsString());
            }
            if (part.has("functionCall") && part.get("functionCall").isJsonObject()) {
                JsonObject functionCall = part.getAsJsonObject("functionCall");
                String toolName = functionCall.has("name") ? functionCall.get("name").getAsString() : "";
                if (toolName.isBlank()) {
                    continue;
                }
                JsonObject toolCall = new JsonObject();
                toolCall.addProperty("id", "call_" + UUID.randomUUID());
                toolCall.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", toolName);
                JsonElement args = functionCall.get("args");
                if (args != null && !args.isJsonNull()) {
                    function.addProperty("arguments", GSON.toJson(args));
                } else {
                    function.addProperty("arguments", "{}");
                }
                toolCall.add("function", function);
                result.toolCalls.add(toolCall);
            }
        }

        result.text = text.toString();
        return result;
    }

    private static String readStringContent(JsonObject message) {
        if (!message.has("content") || message.get("content").isJsonNull()) {
            return "";
        }
        JsonElement contentElement = message.get("content");
        if (contentElement.isJsonPrimitive()) {
            return contentElement.getAsString();
        }
        if (!contentElement.isJsonArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement partElement : contentElement.getAsJsonArray()) {
            if (!partElement.isJsonObject()) {
                continue;
            }
            JsonObject part = partElement.getAsJsonObject();
            String type = part.has("type") ? part.get("type").getAsString() : "";
            if ("text".equals(type) && part.has("text")) {
                text.append(part.get("text").getAsString());
            }
        }
        return text.toString();
    }

    /**
     * JsonElement を JsonObject に安全に変換します。変換できない場合は空の JsonObject を返します。
     *
     * @param source 変換元の JsonElement
     * @return JsonObject（変換不能時は空のオブジェクト）
     */
    private static JsonObject parseJsonObjectOrEmpty(JsonElement source) {
        if (Objects.isNull(source) || source.isJsonNull()) {
            return new JsonObject();
        }
        try {
            if (source.isJsonObject()) {
                return source.getAsJsonObject();
            }
            if (source.isJsonPrimitive()) {
                JsonElement parsed = JsonParser.parseString(source.getAsString());
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new JsonObject();
    }

    private static JsonArray convertOpenAiToolsToGeminiTools(JsonObject openAiRoot) {
        JsonArray result = new JsonArray();
        if (!openAiRoot.has("tools") || !openAiRoot.get("tools").isJsonArray()) {
            return result;
        }

        JsonArray declarations = new JsonArray();
        for (JsonElement toolElement : openAiRoot.getAsJsonArray("tools")) {
            if (!toolElement.isJsonObject()) {
                continue;
            }
            JsonObject tool = toolElement.getAsJsonObject();
            String type = tool.has("type") ? tool.get("type").getAsString() : "";
            if (!"function".equals(type) || !tool.has("function") || !tool.get("function").isJsonObject()) {
                continue;
            }
            JsonObject function = tool.getAsJsonObject("function");
            if (!function.has("name")) {
                continue;
            }

            JsonObject declaration = new JsonObject();
            declaration.addProperty("name", function.get("name").getAsString());
            if (function.has("description") && !function.get("description").isJsonNull()) {
                declaration.addProperty("description", function.get("description").getAsString());
            }
            if (function.has("parameters") && function.get("parameters").isJsonObject()) {
                declaration.add("parameters", function.getAsJsonObject("parameters"));
            }
            declarations.add(declaration);
        }

        if (!declarations.isEmpty()) {
            JsonObject toolsWrapper = new JsonObject();
            toolsWrapper.add("functionDeclarations", declarations);
            result.add(toolsWrapper);
        }
        return result;
    }

    /**
     * OpenAI の role 文字列が有効な role かを判定します。
     *
     * @param role 判定対象の role 文字列
     * @return 有効な role であれば true
     */
    private static boolean isOpenAiRole(String role) {
        jp.euks.myagent2.chat.ChatRole r = jp.euks.myagent2.chat.ChatRole.parse(role);
        return r == jp.euks.myagent2.chat.ChatRole.SYSTEM || r == jp.euks.myagent2.chat.ChatRole.USER || r == jp.euks.myagent2.chat.ChatRole.ASSISTANT;
    }

    /**
     * null を空文字に変換します。
     *
     * @param text 入力文字列
     * @return null の場合は空文字、それ以外は元の文字列
     */
    private static String nullSafe(String text) {
        return Objects.isNull(text) ? "" : text;
    }

    /**
     * Gemini レスポンスから抽出したテキスト・ツール呼び出し情報・フォールバックメッセージを格納するクラス。
     */
    private static final class ResponseParts {
        private String text = "";
        private String fallbackMessage = "";
        private final JsonArray toolCalls = new JsonArray();
    }
}


