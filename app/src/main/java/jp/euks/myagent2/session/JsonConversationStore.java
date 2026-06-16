package jp.euks.myagent2.session;



import java.util.Objects;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * JSONファイルを使って会話履歴を永続化する実装。
 */
public class JsonConversationStore implements ConversationStore {
    private static final String APP_DIR = ".myagent2";
    private static final String SESSIONS_DIR = "sessions";
    private static final String INDEX_FILE = "index.json";

    private final Path sessionsDir;
    private final Clock clock;
    private final Gson gson;

    /**
     * コンストラクタ。
     *
     * @param baseDir アプリケーションデータの基点ディレクトリ（ワークスペースルート等）
     */
    public JsonConversationStore(Path baseDir) {
        this(baseDir, Clock.systemDefaultZone());
    }

    /**
     * テスト用コンストラクタ。
     *
     * @param baseDir アプリケーションデータの基点ディレクトリ
     * @param clock   注入する Clock（テスト用）
     */
    JsonConversationStore(Path baseDir, Clock clock) {
        this.sessionsDir = baseDir.resolve(APP_DIR).resolve(SESSIONS_DIR);
        this.clock = clock;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    @Override
    public ConversationSession loadLatestOrCreate() {
        ensureDirs();

        Optional<ConversationSession> latest = loadLatestSession();
        if (latest.isPresent()) {
            return latest.get();
        }

        ConversationSession created = ConversationSession.createNew(clock);
        save(created);
        return created;
    }

    @Override
    public ConversationSession loadByIdOrCreate(String sessionId) {
        ensureDirs();

        Optional<ConversationSession> existing = readSession(sessionId);
        if (existing.isPresent()) {
            return existing.get();
        }

        ConversationSession created = ConversationSession.createNew(clock);
        save(created);
        return created;
    }

    @Override
    public List<SessionSummary> listSessions() {
        ensureDirs();

        Optional<IndexData> indexOpt = readIndex();
        if (indexOpt.isEmpty() || Objects.isNull(indexOpt.get().sessions)) {
            return List.of();
        }

        return indexOpt.get().sessions.stream()
                .sorted(Comparator.comparing((SessionSummaryData s) -> nullSafe(s.updatedAt)).reversed())
                .map(s -> new SessionSummary(s.sessionId, s.title, s.updatedAt))
                .toList();
    }

    @Override
    public ConversationSession createNewSession() {
        ensureDirs();

        ConversationSession created = ConversationSession.createNew(clock);
        save(created);
        return created;
    }

    @Override
    public void save(ConversationSession session) {
        ensureDirs();
        session.touch(clock);

        writeText(sessionPath(session.sessionId()), gson.toJson(session));

        IndexData index = readIndex().orElseGet(IndexData::new);
        index.latestSessionId = session.sessionId();
        upsertSummary(index, session);
        writeText(indexPath(), gson.toJson(index));
    }

    @Override
    public void saveWithoutTouch(ConversationSession session) {
        ensureDirs();
        // Do NOT touch the session.updatedAt — preserve it.
        writeText(sessionPath(session.sessionId()), gson.toJson(session));

        IndexData index = readIndex().orElseGet(IndexData::new);
        // Do not modify latestSessionId when persisting due to eviction/flush.
        upsertSummary(index, session);
        writeText(indexPath(), gson.toJson(index));
    }

    @Override
    public void deleteSession(String sessionId) {
        ensureDirs();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        deleteSessionFileIfExists(sessionId);

        IndexData index = readIndex().orElseGet(IndexData::new);
        if (Objects.isNull(index.sessions)) {
            index.sessions = new ArrayList<>();
        }

        index.sessions.removeIf(s -> sessionId.equals(s.sessionId));

        if (sessionId.equals(index.latestSessionId)) {
            index.latestSessionId = pickLatestSessionId(index.sessions);
        }

        writeText(indexPath(), gson.toJson(index));
    }

    private Optional<ConversationSession> loadLatestSession() {
        Optional<IndexData> indexOpt = readIndex();
        if (indexOpt.isEmpty()) {
            return Optional.empty();
        }

        IndexData index = indexOpt.get();
        if (index.latestSessionId != null && !index.latestSessionId.isBlank()) {
            Optional<ConversationSession> latestById = readSession(index.latestSessionId);
            if (latestById.isPresent()) {
                return latestById;
            }
        }

        if (Objects.isNull(index.sessions) || index.sessions.isEmpty()) {
            return Optional.empty();
        }

        return index.sessions.stream()
                .sorted(Comparator.comparing((SessionSummaryData s) -> nullSafe(s.updatedAt)).reversed())
                .map(summary -> readSession(summary.sessionId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /**
     * セッションファイルを読み込み ConversationSession を復元します。
     *
     * @param sessionId 読み込み対象のセッション ID
     * @return セッションが存在すれば Optional に格納して返す
     */
    private Optional<ConversationSession> readSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        Path file = sessionPath(sessionId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        String json = readText(file);
        if (json.isBlank()) {
            return Optional.empty();
        }

        try {
            ConversationSession session = gson.fromJson(json, ConversationSession.class);
            if (Objects.isNull(session) || session.sessionId() == null || session.sessionId().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(session);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * セッション一覧インデックスを読み込みます。
     *
     * @return IndexData を Optional で返す（存在しなければ空）
     */
    private Optional<IndexData> readIndex() {
        Path indexFile = indexPath();
        if (!Files.exists(indexFile)) {
            return Optional.empty();
        }

        String json = readText(indexFile);
        if (json.isBlank()) {
            return Optional.empty();
        }

        try {
            IndexData data = gson.fromJson(json, IndexData.class);
            if (Objects.isNull(data)) {
                return Optional.empty();
            }
            if (Objects.isNull(data.sessions)) {
                data.sessions = new ArrayList<>();
            }
            return Optional.of(data);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * インデックス内にセッション要約を追加または更新します。
     *
     * @param index   インデックスオブジェクト
     * @param session 更新対象のセッション
     */
    private void upsertSummary(IndexData index, ConversationSession session) {
        if (Objects.isNull(index.sessions)) {
            index.sessions = new ArrayList<>();
        }

        for (SessionSummaryData summary : index.sessions) {
            if (session.sessionId().equals(summary.sessionId)) {
                summary.title = session.title();
                summary.updatedAt = session.updatedAt();
                return;
            }
        }

        SessionSummaryData created = new SessionSummaryData();
        created.sessionId = session.sessionId();
        created.title = session.title();
        created.updatedAt = session.updatedAt();
        index.sessions.add(created);
    }

    /**
     * 永続化用ディレクトリを作成します。
     */
    private void ensureDirs() {
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            throw new IllegalStateException("会話履歴ディレクトリの作成に失敗しました: " + sessionsDir, e);
        }
    }

    /**
     * インデックスファイルの Path を返します。
     *
     * @return index.json の Path
     */
    private Path indexPath() {
        return sessionsDir.resolve(INDEX_FILE);
    }

    /**
     * 指定セッションのファイルパスを返します。
     *
     * @param sessionId セッション ID
     * @return セッション JSON ファイルの Path
     */
    private Path sessionPath(String sessionId) {
        return sessionsDir.resolve(sessionId + ".json");
    }

    /**
     * ファイルを UTF-8 で読み取り文字列として返します。失敗時は空文字。
     *
     * @param file 読み取り対象のファイルパス
     * @return ファイル内容または空文字
     */
    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * テキストを UTF-8 で書き込みます。失敗時は IllegalStateException をスローします。
     *
     * @param file 書き込み先の Path
     * @param text 書き込むテキスト
     */
    private void writeText(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("会話履歴ファイルの保存に失敗しました: " + file, e);
        }
    }

    /**
     * セッションファイルを削除します（存在しない場合は無視）。
     *
     * @param sessionId 削除対象のセッション ID
     */
    private void deleteSessionFileIfExists(String sessionId) {
        try {
            Files.deleteIfExists(sessionPath(sessionId));
        } catch (IOException e) {
            throw new IllegalStateException("会話履歴ファイルの削除に失敗しました: " + sessionPath(sessionId), e);
        }
    }

    /**
     * セッション一覧から最新のセッション ID を選択して返します。
     *
     * @param summaries セッション要約のリスト
     * @return 最新セッション ID（存在しなければ空文字）
     */
    private String pickLatestSessionId(List<SessionSummaryData> summaries) {
        if (Objects.isNull(summaries) || summaries.isEmpty()) {
            return "";
        }
        return summaries.stream()
                .sorted(Comparator.comparing((SessionSummaryData s) -> nullSafe(s.updatedAt)).reversed())
                .map(s -> s.sessionId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String nullSafe(String value) {
        return Objects.isNull(value) ? "" : value;
    }

    private static final class IndexData {
        String latestSessionId = "";
        List<SessionSummaryData> sessions = new ArrayList<>();
    }

    private static final class SessionSummaryData {
        String sessionId;
        String title;
        String updatedAt;
    }
}



