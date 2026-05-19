package jp.euks.myagent2.session;

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

    public JsonConversationStore(Path baseDir) {
        this(baseDir, Clock.systemDefaultZone());
    }

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
        if (indexOpt.isEmpty() || indexOpt.get().sessions == null) {
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
    public void deleteSession(String sessionId) {
        ensureDirs();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        deleteSessionFileIfExists(sessionId);

        IndexData index = readIndex().orElseGet(IndexData::new);
        if (index.sessions == null) {
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

        if (index.sessions == null || index.sessions.isEmpty()) {
            return Optional.empty();
        }

        return index.sessions.stream()
            .sorted(Comparator.comparing((SessionSummaryData s) -> nullSafe(s.updatedAt)).reversed())
            .map(summary -> readSession(summary.sessionId))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst();
    }

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
            if (session == null || session.sessionId() == null || session.sessionId().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(session);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

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
            if (data == null) {
                return Optional.empty();
            }
            if (data.sessions == null) {
                data.sessions = new ArrayList<>();
            }
            return Optional.of(data);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void upsertSummary(IndexData index, ConversationSession session) {
        if (index.sessions == null) {
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

    private void ensureDirs() {
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            throw new IllegalStateException("会話履歴ディレクトリの作成に失敗しました: " + sessionsDir, e);
        }
    }

    private Path indexPath() {
        return sessionsDir.resolve(INDEX_FILE);
    }

    private Path sessionPath(String sessionId) {
        return sessionsDir.resolve(sessionId + ".json");
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private void writeText(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("会話履歴ファイルの保存に失敗しました: " + file, e);
        }
    }

    private void deleteSessionFileIfExists(String sessionId) {
        try {
            Files.deleteIfExists(sessionPath(sessionId));
        } catch (IOException e) {
            throw new IllegalStateException("会話履歴ファイルの削除に失敗しました: " + sessionPath(sessionId), e);
        }
    }

    private String pickLatestSessionId(List<SessionSummaryData> summaries) {
        if (summaries == null || summaries.isEmpty()) {
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
        return value == null ? "" : value;
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
