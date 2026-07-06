package jp.euks.myagent2.chat;

import java.util.Objects;
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
    static final String ENV_API_KEY_OPENAI = "MYAGENT2_API_KEY_OPENAI";
    static final String ENV_API_KEY_GEMINI = "MYAGENT2_API_KEY_GEMINI";
    static final String ENV_BASE_URL_OPENAI = "MYAGENT2_BASE_URL_OPENAI";
    static final String ENV_BASE_URL_GEMINI = "MYAGENT2_BASE_URL_GEMINI";
    // 後方互換性のため、これらは保持するがドキュメントでは非推奨とする
    static final String ENV_API_KEY = "MYAGENT2_API_KEY";
    static final String ENV_BASE_URL = "MYAGENT2_BASE_URL";
    static final String ENV_MODEL = "MYAGENT2_MODEL";

    private static final String DEFAULT_BASE_URL_OPENAI = "https://api.openai.com/v1";
    private static final String DEFAULT_BASE_URL_GEMINI = "https://generativelanguage.googleapis.com/v1beta";
    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_GEMINI = "gemini";

    private ChatServiceFactory() {
    }

    /**
     * 環境変数に基づいて既定の ChatService 実装を生成する。
     * 設定済みの最初のプロバイダーを使用する。
     * 
     * @return ChatService 実装
     */
    public static ChatService createDefault() {
        Path workDir = Path.of(System.getProperty("user.dir"));
        return createForSessionWithProvider(workDir, null);
    }

    /**
     * 指定した作業ディレクトリとプロバイダーを使ってセッション用の ChatService を生成する。
     * プロバイダーが null または空の場合は、最初に設定されているプロバイダーを使用する。
     * 
     * @param workDir    ツールがアクセスする作業ディレクトリ
     * @param provider   セッション固有のプロバイダー（"openai" または "gemini"、null / 空なら最初に設定されているもの）
     * @return ChatService 実装
     */
    public static ChatService createForSessionWithProvider(Path workDir, String provider) {
        Path resolvedWorkDir = (Objects.isNull(workDir)) ? Path.of(System.getProperty("user.dir")) : workDir;
        Map<String, String> env = System.getenv();

        String resolvedProvider = trimToEmpty(provider);
        if (resolvedProvider.isEmpty()) {
            resolvedProvider = getFirstAvailableProvider();
        }

        if (PROVIDER_OPENAI.equals(resolvedProvider)) {
            return createOpenAiService(env, resolvedWorkDir);
        } else if (PROVIDER_GEMINI.equals(resolvedProvider)) {
            return createGeminiService(env, resolvedWorkDir);
        }

        return new StubChatService();
    }

    /**
     * 設定済みのプロバイダーを取得します。OpenAI が優先、次に Gemini。
     * 
     * @return "openai" または "gemini" または ""
     */
    static String getFirstAvailableProvider() {
        Map<String, String> env = System.getenv();
        if (!trimToEmpty(env.get(ENV_API_KEY_OPENAI)).isEmpty() &&
            !trimToEmpty(env.get(ENV_BASE_URL_OPENAI)).isEmpty()) {
            return PROVIDER_OPENAI;
        }
        if (!trimToEmpty(env.get(ENV_API_KEY_GEMINI)).isEmpty() &&
            !trimToEmpty(env.get(ENV_BASE_URL_GEMINI)).isEmpty()) {
            return PROVIDER_GEMINI;
        }
        return "";
    }

    /**
     * 設定済みのプロバイダーのリストを返します。
     * 
     * @return ["openai", "gemini"] のサブセット
     */
    static java.util.List<String> getAvailableProviders() {
        java.util.List<String> providers = new java.util.ArrayList<>();
        Map<String, String> env = System.getenv();
        
        if (!trimToEmpty(env.get(ENV_API_KEY_OPENAI)).isEmpty() &&
            !trimToEmpty(env.get(ENV_BASE_URL_OPENAI)).isEmpty()) {
            providers.add(PROVIDER_OPENAI);
        }
        if (!trimToEmpty(env.get(ENV_API_KEY_GEMINI)).isEmpty() &&
            !trimToEmpty(env.get(ENV_BASE_URL_GEMINI)).isEmpty()) {
            providers.add(PROVIDER_GEMINI);
        }
        
        return providers;
    }

    /**
     * OpenAI 用の ChatService を生成します。
     */
    private static ChatService createOpenAiService(Map<String, String> env, Path workDir) {
        String apiKey = trimToEmpty(env.get(ENV_API_KEY_OPENAI));
        if (apiKey.isEmpty()) {
            return new StubChatService();
        }

        String baseUrl = trimToEmpty(env.get(ENV_BASE_URL_OPENAI));
        if (baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE_URL_OPENAI;
        }
        baseUrl = normalizeBaseUrl(baseUrl);

        return new OpenAiCompatibleChatService(
                baseUrl,
                apiKey,
                "gpt-4o-mini",
                new WorkspaceGrepTool(workDir),
                new GitLogTool(workDir),
                new FileReaderTool(workDir),
                new FileWriterTool(workDir),
                new LocalCommandTool(workDir));
    }

    /**
     * Gemini 用の ChatService を生成します。
     * LangChain4j のネイティブ GoogleAiGeminiChatModel を使用し、
     * ツール統合とストリーミングに対応しています。
     */
    private static ChatService createGeminiService(Map<String, String> env, Path workDir) {
        String apiKey = trimToEmpty(env.get(ENV_API_KEY_GEMINI));
        if (apiKey.isEmpty()) {
            return new StubChatService();
        }

        return new GeminiNativeChatService(
                apiKey,
                "gemini-2.0-flash",
                new WorkspaceGrepTool(workDir),
                new GitLogTool(workDir),
                new FileReaderTool(workDir),
                new FileWriterTool(workDir),
                new LocalCommandTool(workDir));
    }

    /**
     * 環境変数と作業ディレクトリを使用して ChatService を生成する内部メソッド。
     * 
     * @param env     環境変数のマップ
     * @param workDir ツールがアクセスする作業ディレクトリ
     * @return ChatService 実装
     */
    private static ChatService createFromEnvWithWorkDir(Map<String, String> env, Path workDir) {
        // This method is now replaced by createForSessionWithProvider
        // Kept for backward compatibility during testing
        String apiKey = trimToEmpty(env.get(ENV_API_KEY));
        if (apiKey.isEmpty()) {
            return new StubChatService();
        }

        String baseUrl = trimToEmpty(env.getOrDefault(ENV_BASE_URL, DEFAULT_BASE_URL_OPENAI));
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl.isEmpty() ? DEFAULT_BASE_URL_OPENAI : baseUrl);
        EndpointAuth endpointAuth = resolveEndpoint(normalizedBaseUrl, apiKey);

        return new OpenAiCompatibleChatService(
                endpointAuth.baseUrl(),
                endpointAuth.apiKey(),
                "gpt-4o-mini",
                new WorkspaceGrepTool(workDir),
                new GitLogTool(workDir),
                new FileReaderTool(workDir),
                new FileWriterTool(workDir),
                new LocalCommandTool(workDir));
    }

    /**
     * ベースURLがGoogleを含む場合はプロキシサーバーを起動してエンドポイントを解決するユーティリティ。
     * 
     * @param normalizedBaseUrl 正規化されたベースURL
     * @param apiKey            APIキー
     * @return エンドポイント情報を含む EndpointAuth レコード
     */
    private static EndpointAuth resolveEndpoint(String normalizedBaseUrl, String apiKey) {
        if (!containsGoogleHost(normalizedBaseUrl)) {
            return new EndpointAuth(normalizedBaseUrl, apiKey);
        }
        GeminiOpenAiProxyServer.ProxyEndpoint endpoint = GeminiOpenAiProxyServer.ensureStarted(normalizedBaseUrl,
                apiKey);
        return new EndpointAuth(endpoint.baseUrl(), endpoint.apiKey());
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
        if (Objects.isNull(value)) {
            return "";
        }
        return value.trim();
    }

    /**
     * エンドポイントのベースURLとAPIキーを保持するレコードクラス。
     * 
     * @param baseUrl エンドポイントのベースURL
     * @param apiKey  エンドポイントにアクセスするためのAPIキー
     */
    private record EndpointAuth(String baseUrl, String apiKey) {
    }
}
