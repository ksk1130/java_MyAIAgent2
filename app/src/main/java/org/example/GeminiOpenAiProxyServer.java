package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * OpenAI Chat Completions 互換のローカルプロキシ。
 * 受け取った OpenAI 形式 JSON を Gemini 形式へ変換して転送する。
 */
public final class GeminiOpenAiProxyServer {
    private static final Logger log = LoggerFactory.getLogger(GeminiOpenAiProxyServer.class);
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final Map<String, GeminiOpenAiProxyServer> SERVERS = new ConcurrentHashMap<>();

    private final HttpServer server;
    private final HttpClient httpClient;
    private final String geminiBaseUrl;
    private final String apiKey;

    private GeminiOpenAiProxyServer(HttpServer server, String geminiBaseUrl, String apiKey) {
        this.server = server;
        this.httpClient = HttpClient.newHttpClient();
        this.geminiBaseUrl = geminiBaseUrl;
        this.apiKey = apiKey;
    }

    public static ProxyEndpoint ensureStarted(String geminiBaseUrl, String apiKey) {
        String normalized = normalizeBaseUrl(geminiBaseUrl);
        String key = normalized + "::" + apiKey;
        GeminiOpenAiProxyServer existing = SERVERS.get(key);
        if (existing != null) {
            return existing.endpoint();
        }

        synchronized (GeminiOpenAiProxyServer.class) {
            existing = SERVERS.get(key);
            if (existing != null) {
                return existing.endpoint();
            }
            GeminiOpenAiProxyServer created = start(normalized, apiKey);
            SERVERS.put(key, created);
            return created.endpoint();
        }
    }

    private static GeminiOpenAiProxyServer start(String geminiBaseUrl, String apiKey) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(LOCAL_HOST, 0), 0);
            GeminiOpenAiProxyServer proxy = new GeminiOpenAiProxyServer(server, geminiBaseUrl, apiKey);
            server.createContext("/v1/chat/completions", proxy.new ChatCompletionsHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            log.info("Gemini OpenAI proxy started: host={}, port={}, geminiBaseUrl={}", LOCAL_HOST, server.getAddress().getPort(), geminiBaseUrl);
            return proxy;
        } catch (IOException e) {
            throw new IllegalStateException("Gemini OpenAIプロキシ起動に失敗しました", e);
        }
    }

    private ProxyEndpoint endpoint() {
        int port = server.getAddress().getPort();
        return new ProxyEndpoint("http://" + LOCAL_HOST + ":" + port + "/v1", "proxy-local");
    }

    private final class ChatCompletionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestId = UUID.randomUUID().toString();
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                log.warn("[{}] Unsupported method: {} path={}", requestId, exchange.getRequestMethod(), exchange.getRequestURI());
                writeJson(exchange, 405, "{\"error\":{\"message\":\"method not allowed\"}}");
                return;
            }

            String openAiRequestJson = readBody(exchange.getRequestBody());
            String model = extractModel(openAiRequestJson);
            log.debug("[{}] Proxy request received: model={}, path={}, bodyBytes={}",
                requestId,
                model,
                exchange.getRequestURI(),
                openAiRequestJson.getBytes(StandardCharsets.UTF_8).length);
            log.debug("[{}] OpenAI request json: {}", requestId, abbreviate(openAiRequestJson, 4000));

            String geminiRequestJson = GeminiOpenAiJsonProxy.toGeminiRequestJson(openAiRequestJson);
            log.debug("[{}] Converted Gemini request json: {}", requestId, abbreviate(geminiRequestJson, 4000));

            String targetUrl = buildGeminiGenerateContentUrl(model);
            log.debug("[{}] Forwarding to Gemini endpoint: {}", requestId, targetUrl);

            HttpRequest request = HttpRequest.newBuilder(URI.create(targetUrl))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(geminiRequestJson, StandardCharsets.UTF_8))
                .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                log.debug("[{}] Gemini response: status={}, bodyBytes={}",
                    requestId,
                    response.statusCode(),
                    response.body() == null ? 0 : response.body().getBytes(StandardCharsets.UTF_8).length);
                log.debug("[{}] Gemini response summary: {}",
                    requestId,
                    GeminiOpenAiJsonProxy.summarizeGeminiResponse(response.body()));
                log.debug("[{}] Gemini response body: {}", requestId, abbreviate(response.body(), 4000));

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorJson = "{\"error\":{\"message\":\"Gemini proxy failed: HTTP " + response.statusCode() + "\",\"details\":"
                        + quoteJson(response.body()) + "}}";
                    log.warn("[{}] Gemini non-success response converted to OpenAI error", requestId);
                    writeJson(exchange, 502, errorJson);
                    return;
                }

                String openAiResponseJson = GeminiOpenAiJsonProxy.toOpenAiResponseJson(response.body(), model);
                log.debug("[{}] OpenAI response summary: {}",
                    requestId,
                    GeminiOpenAiJsonProxy.summarizeOpenAiResponse(openAiResponseJson));
                log.debug("[{}] Converted OpenAI response json: {}", requestId, abbreviate(openAiResponseJson, 4000));
                writeJson(exchange, 200, openAiResponseJson);
                log.debug("[{}] Proxy response returned: status=200", requestId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[{}] Proxy interrupted while waiting Gemini response", requestId, e);
                writeJson(exchange, 500, "{\"error\":{\"message\":\"Gemini proxy interrupted\"}}");
            } catch (RuntimeException e) {
                log.error("[{}] Proxy runtime error during conversion or forwarding", requestId, e);
                writeJson(exchange, 500, "{\"error\":{\"message\":\"Gemini proxy runtime error\"}}");
            }
        }
    }

    private String buildGeminiGenerateContentUrl(String model) {
        String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20");
        return geminiBaseUrl + "/models/" + encodedModel + ":generateContent";
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String extractModel(String openAiRequestJson) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(openAiRequestJson).getAsJsonObject();
            if (root.has("model") && !root.get("model").isJsonNull()) {
                String model = root.get("model").getAsString();
                if (!model.isBlank()) {
                    return model;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "gemini-2.0-flash";
    }

    private static String quoteJson(String text) {
        if (text == null) {
            return "\"\"";
        }
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\"";
    }

    private static String abbreviate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (maxLen <= 0 || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "... (truncated)";
    }

    public record ProxyEndpoint(String baseUrl, String apiKey) {
    }
}