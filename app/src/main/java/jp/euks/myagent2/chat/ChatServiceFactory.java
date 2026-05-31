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
        Path workDir = Path.of(System.getProperty("user.dir"));
        return createFromEnvWithWorkDir(env, workDir);
    }

    /**
     * 指定した作業ディレクトリを使ってセッション用の ChatService を生成する。
     * 環境変数は実行環境の値を使用する。
     */
    public static ChatService createForSession(Path workDir) {
        Path resolvedWorkDir = (Objects.isNull(workDir)) ? Path.of(System.getProperty("user.dir")) : workDir;

        return createFromEnvWithWorkDir(System.getenv(), resolvedWorkDir);
    }

    /**
     * 環境変数と作業ディレクトリを使用して ChatService を生成する内部メソッド。
     * 
     * @param env     環境変数のマップ
     * @param workDir ツールがアクセスする作業ディレクトリ
     * @return ChatService 実装
     */
    private static ChatService createFromEnvWithWorkDir(Map<String, String> env, Path workDir) {
        String apiKey = trimToEmpty(env.get(ENV_API_KEY));
        if (apiKey.isEmpty()) {
            return new StubChatService();
        }

        String baseUrl = trimToEmpty(env.getOrDefault(ENV_BASE_URL, DEFAULT_BASE_URL));
        String model = trimToEmpty(env.getOrDefault(ENV_MODEL, DEFAULT_MODEL));
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl);
        String resolvedModel = model.isEmpty() ? DEFAULT_MODEL : model;
        EndpointAuth endpointAuth = resolveEndpoint(normalizedBaseUrl, apiKey);

        return new OpenAiCompatibleChatService(
                endpointAuth.baseUrl(),
                endpointAuth.apiKey(),
                resolvedModel,
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
