package jp.euks.myagent2.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * ワークスペース内のファイルを検索するユーティリティ。
 * 大きなファイルやビルド出力ディレクトリを除外し、結果を整形して返す。
 */
public class WorkspaceGrepTool {
    private static final int DEFAULT_MAX_MATCHES = 1_000;
    private static final int MAX_FILE_SIZE_BYTES = 1_000_000;

    private final Path rootDir;
    private final int maxMatches;

    public WorkspaceGrepTool(Path rootDir) {
        this(rootDir, DEFAULT_MAX_MATCHES);
    }

    WorkspaceGrepTool(Path rootDir, int maxMatches) {
        this.rootDir = rootDir;
        this.maxMatches = maxMatches;
    }

    /**
     * 指定したクエリ文字列でワークスペース内のファイルを検索し、結果をフォーマットして返す。
     * 結果は `(tool:grep) N件\npath:line | text` の形式になる。
     * 空クエリや内部I/Oエラーはエラーメッセージを返す。
     *
     * フォーマット例：
     *   "Chat"              → Chat を含む行を検索
     *   "Chat -v Test"      → Chat を含むが Test を含まない行を検索
     *   "Chat --exclude Stub" → Chat を含むが Stub を含まない行を検索
     *
     * @param query 検索語（部分一致、大小区別なし）、オプションで " -v exclude" または " --exclude exclude"
     * @return フォーマットされた検索結果またはエラー文字列
     */
    public String search(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return "(tool:error) 空の検索語は指定できません。";
        }

        // クエリをパース: "query -v exclude" または "query --exclude exclude"
        String searchPattern = trimmed;
        String excludePattern = null;

        if (trimmed.contains(" -v ")) {
            String[] parts = trimmed.split(" -v ", 2);
            searchPattern = parts[0].trim();
            excludePattern = parts[1].trim();
        } else if (trimmed.contains(" --exclude ")) {
            String[] parts = trimmed.split(" --exclude ", 2);
            searchPattern = parts[0].trim();
            excludePattern = parts[1].trim();
        }

        if (searchPattern.isEmpty()) {
            return "(tool:error) 検索パターンが空です。";
        }

        try {
            List<String> matches = collectMatches(searchPattern, excludePattern);
            if (matches.isEmpty()) {
                String desc = excludePattern == null
                    ? "'" + searchPattern + "' は見つかりませんでした"
                    : "'" + searchPattern + "' で '" + excludePattern + "' を除いた結果は見つかりませんでした";
                return "(tool:grep) 0件: " + desc;
            }
            return "(tool:grep) " + matches.size() + "件\n" + String.join("\n", matches);
        } catch (IOException e) {
            return "(tool:error) grep実行中に失敗しました: " + e.getMessage();
        }
    }

    private List<String> collectMatches(String searchPattern, String excludePattern) throws IOException {
        List<String> results = new ArrayList<>();
        String needle = searchPattern.toLowerCase(Locale.ROOT);
        String excludeNeedle = excludePattern == null ? null : excludePattern.toLowerCase(Locale.ROOT);

        try (Stream<Path> stream = Files.walk(rootDir)) {
            List<Path> candidates = stream
                .filter(Files::isRegularFile)
                .filter(this::isSearchTarget)
                .toList();

            for (Path file : candidates) {
                collectFileMatches(file, needle, excludeNeedle, results);
                if (results.size() >= maxMatches) {
                    break;
                }
            }
        }

        return results;
    }

    private void collectFileMatches(Path file, String needle, String excludeNeedle, List<String> results) {
        List<String> lines = null;
        // まずUTF-8で試行、失敗時はShift_JIS(CP932/Windows-31J)で再試行
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e1) {
            try {
                lines = Files.readAllLines(file, java.nio.charset.Charset.forName("Windows-31J"));
            } catch (IOException | RuntimeException e2) {
                // どちらも失敗した場合はスキップ
                return;
            }
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lineLower = line.toLowerCase(Locale.ROOT);
            // 検索パターンを含む か つ 除外パターンを含まない行を取得
            if (lineLower.contains(needle)) {
                if (excludeNeedle == null || !lineLower.contains(excludeNeedle)) {
                    results.add(formatMatch(file, i + 1, line));
                    if (results.size() >= maxMatches) {
                        return;
                    }
                }
            }
        }
    }

    private boolean isSearchTarget(Path file) {
        String normalized = rootDir.relativize(file).toString().replace('\\', '/');
        if (normalized.startsWith("build/") || normalized.startsWith(".gradle/") || normalized.startsWith("bin/")) {
            return false;
        }

        try {
            return Files.size(file) <= MAX_FILE_SIZE_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private String formatMatch(Path file, int lineNumber, String line) {
        String normalized = rootDir.relativize(file).toString().replace('\\', '/');
        String singleLine = line.strip();
        if (singleLine.length() > 120) {
            singleLine = singleLine.substring(0, 120) + "...";
        }
        return normalized + ":" + lineNumber + " | " + singleLine;
    }
}