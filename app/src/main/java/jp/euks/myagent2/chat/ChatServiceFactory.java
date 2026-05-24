package jp.euks.myagent2.chat;

import jp.euks.myagent2.proxy.*;
import jp.euks.myagent2.tools.*;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * 環境変数に基づいて `ChatService` の実装を生成するファクトリクラス。
 *
 * <p>
 * `OPENAI_API_KEY` が設定されていれば `OpenAiCompatibleChatService` を生成し、
 * 未設定の場合は `StubChatService` を返します。テスト時に環境を模擬する
 * `createFromEnv` メソッドも提供します。
 */
public final class ChatServiceFactory {
    static final String ENV_API_KEY = "MYAGENT2_API_KEY";
    static final String ENV_BASE_URL = "MYAGENT2_BASE_URL";
    static final String ENV_MODEL = "MYAGENT2_MODEL";

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private ChatServiceFactory() {
    }

    /**
     * 環境変数に基づいて既定の ChatService 実装を生成する。
     * API キーが設定されていれば `OpenAiCompatibleChatService` を、なければ `StubChatService` を返す。
     *
     * @return ChatService 実装
     */
    public static ChatService createDefault() {
        return createFromEnv(System.getenv());
    }

    /**
     * テスト可能性のため環境マップからサービスを生成する内部メソッド。
     *
     * @param env 環境変数のマップ
     * @return ChatService 実装
     */
    static ChatService createFromEnv(Map<String, String> env) {
        String apiKey = trimToEmpty(env.get(ENV_API_KEY));
        if (apiKey.isEmpty()) {
            return new StubChatService();
        }

        String baseUrl = trimToEmpty(env.getOrDefault(ENV_BASE_URL, DEFAULT_BASE_URL));
        String model = trimToEmpty(env.getOrDefault(ENV_MODEL, DEFAULT_MODEL));
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl);

        Path workDir = Path.of(System.getProperty("user.dir"));
        String effectiveBaseUrl = normalizedBaseUrl;
        String effectiveApiKey = apiKey;

        if (containsGoogleHost(normalizedBaseUrl)) {
            GeminiOpenAiProxyServer.ProxyEndpoint endpoint = GeminiOpenAiProxyServer.ensureStarted(normalizedBaseUrl, apiKey);
            effectiveBaseUrl = endpoint.baseUrl();
            effectiveApiKey = endpoint.apiKey();
        }

        return new OpenAiCompatibleChatService(
                effectiveBaseUrl,
                effectiveApiKey,
                model.isEmpty() ? DEFAULT_MODEL : model,
                new WorkspaceGrepTool(workDir),
                new GitLogTool(workDir),
                new FileReaderTool(workDir),
                new FileWriterTool(workDir),
                new LocalCommandTool(workDir));
    }

    /**
     * 指定した作業ディレクトリを使ってセッション用の ChatService を生成する。
     * 環境変数は実行環境の値を使用する。
     */
    public static ChatService createForSession(Path workDir) {
        Map<String, String> env = System.getenv();
        String apiKey = trimToEmpty(env.get(ENV_API_KEY));
        if (apiKey.isEmpty()) {
            return new StubChatService();
        }

        String baseUrl = trimToEmpty(env.getOrDefault(ENV_BASE_URL, DEFAULT_BASE_URL));
        String model = trimToEmpty(env.getOrDefault(ENV_MODEL, DEFAULT_MODEL));
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl);

        String effectiveBaseUrl = normalizedBaseUrl;
        String effectiveApiKey = apiKey;

        if (containsGoogleHost(normalizedBaseUrl)) {
            GeminiOpenAiProxyServer.ProxyEndpoint endpoint = GeminiOpenAiProxyServer.ensureStarted(normalizedBaseUrl, apiKey);
            effectiveBaseUrl = endpoint.baseUrl();
            effectiveApiKey = endpoint.apiKey();
        }

        Path resolvedWorkDir = (workDir == null) ? Path.of(System.getProperty("user.dir")) : workDir;

        return new OpenAiCompatibleChatService(
                effectiveBaseUrl,
                effectiveApiKey,
                model.isEmpty() ? DEFAULT_MODEL : model,
                new WorkspaceGrepTool(resolvedWorkDir),
                new GitLogTool(resolvedWorkDir),
                new FileReaderTool(resolvedWorkDir),
                new FileWriterTool(resolvedWorkDir),
                new LocalCommandTool(resolvedWorkDir));
    }

    /**
     * ベースURLを正規化するユーティリティ。末尾のスラッシュを削除し、前後の空白をトリムする。
     * 
     * @param baseUrl 正規化するURL文字列
     * @return 正規化されたURL文字列
     */
    static String normalizeBaseUrl(String baseUrl) {
        String trimmed = trimToEmpty(baseUrl);
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static boolean containsGoogleHost(String baseUrl) {
        return trimToEmpty(baseUrl).toLowerCase(Locale.ROOT).contains("google");
    }

    /**
     * 文字列をトリムして、nullの場合は空文字に変換するユーティリティ。
     * 
     * @param value
     * @return
     */
    private static String trimToEmpty(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}