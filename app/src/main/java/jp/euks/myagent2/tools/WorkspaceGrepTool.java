package jp.euks.myagent2.tools;



import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * ワークスペース内のファイルを検索するユーティリティ。
 * 大きなファイルやビルド出力ディレクトリを除外し、結果を整形して返す。
 */
public class WorkspaceGrepTool {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceGrepTool.class);
    private static final int DEFAULT_MAX_MATCHES = 1_000;
    private static final int MAX_FILE_SIZE_BYTES = 1_000_000;
    private static final Charset WINDOWS_31J = Charset.forName("Windows-31J");
    private static final int RG_TIMEOUT_SECONDS = 10;

    private final Path rootDir;
    private final int maxMatches;
    private final String resolvedRgExe;
    private final boolean rgAvailable;

    /**
     * 指定されたルートディレクトリでワークスペース検索を行う `WorkspaceGrepTool` を生成します。
     * 
     * @param rootDir 検索対象となるワークスペースのルートディレクトリ
     */
    public WorkspaceGrepTool(Path rootDir) {
        this(rootDir, DEFAULT_MAX_MATCHES);
    }

    /**
     * 指定されたルートディレクトリでワークスペース検索を行う `WorkspaceGrepTool` を生成します。
     *
     * @param rootDir    検索対象となるワークスペースのルートディレクトリ
     * @param maxMatches 返却する最大一致数
     */
    WorkspaceGrepTool(Path rootDir, int maxMatches) {
        this.rootDir = rootDir;
        this.maxMatches = maxMatches;
        this.resolvedRgExe = resolveRgExe();
        this.rgAvailable = isRgAvailable(this.resolvedRgExe);
        if (!this.rgAvailable) {
            log.info("[GREP] rg is unavailable at startup, fallback to Java grep. candidate={}", this.resolvedRgExe);
        }
    }

    /**
     * 内部用コンストラクタ。
     *
     * @param rootDir    検索対象となるワークスペースのルートディレクトリ
     * @param maxMatches 返却する最大一致数
     * @param disableRg  true の場合、外部 `rg` 実行ファイルを無効化して常に Java 実装にフォールバックします
     */
    WorkspaceGrepTool(Path rootDir, int maxMatches, boolean disableRg) {
        this.rootDir = rootDir;
        this.maxMatches = maxMatches;
        if (disableRg) {
            this.resolvedRgExe = "rg";
            this.rgAvailable = false;
        } else {
            this.resolvedRgExe = resolveRgExe();
            this.rgAvailable = isRgAvailable(this.resolvedRgExe);
            if (!this.rgAvailable) {
                log.info("[GREP] rg is unavailable at startup, fallback to Java grep. candidate={}",
                        this.resolvedRgExe);
            }
        }
    }

    /**
     * 指定したクエリ文字列でワークスペース内のファイルを検索し、結果をフォーマットして返す。
     * 結果は `(tool:grep) N件\npath:line | text` の形式になる。
     * 空クエリや内部I/Oエラーはエラーメッセージを返す。
     *
     * フォーマット例：
     * "Chat" → Chat を含む行を検索
     * "Chat -v Test" → Chat を含むが Test を含まない行を検索
     * "Chat --exclude Stub" → Chat を含むが Stub を含まない行を検索
     *
     * @param query 検索語（部分一致、大小区別なし）、オプションで " -v exclude" または " --exclude exclude"
     * @return フォーマットされた検索結果またはエラー文字列
     */
    public String search(String query) {
        String trimmed = Objects.isNull(query) ? "" : query.trim();
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
            GrepResult result = collectMatchesWithSource(searchPattern, excludePattern);
            if (result.matches.isEmpty()) {
                String desc = Objects.isNull(excludePattern)
                        ? "'%s' を含む行は見つかりませんでした".formatted(searchPattern)
                        : "'%s' で '%s' を除いた結果は見つかりませんでした".formatted(searchPattern, excludePattern);
                return "(tool:grep) 0件: " + desc;
            }
            String head = "(tool:grep:%s) %d件".formatted(result.source, result.matches.size());
            return "%s\n%s".formatted(head, String.join("\n", result.matches));
        } catch (IOException e) {
            return "(tool:error) grep実行中に失敗しました: " + e.getMessage();
        }
    }

    /**
     * 検索を実行し、実行元（rg または java）とマッチ一覧を返します。
     *
     * @param searchPattern  検索パターン（部分一致、小文字化されて処理される）
     * @param excludePattern 除外パターン（指定がなければ null）
     * @return 検索を実行したソース名称とマッチ一覧を含む `GrepResult`
     * @throws IOException ファイル走査時の入出力エラー
     */
    private GrepResult collectMatchesWithSource(String searchPattern, String excludePattern) throws IOException {
        // rg(UTF-8専用)で高速検索（利用可能時のみ）
        if (rgAvailable) {
            List<String> rgMatches = collectMatchesByRgUtf(searchPattern, excludePattern);
            if (!rgMatches.isEmpty()) {
                return new GrepResult("rg", rgMatches);
            }
        }
        // Java 実装(Windows-31J専用)でフォールバック
        List<String> javaMatches = collectMatchesByJavaSjis(searchPattern, excludePattern);
        return new GrepResult("java", javaMatches);
    }

    /**
     * 検索結果と実行ソースを保持する内部クラス。
     */
    private static class GrepResult {
        final String source;
        final List<String> matches;

        /**
         * 検索結果を保持する `GrepResult` を生成します。
         * 
         * @param source  検索を実行したソース名称（例: "rg" または "java"）
         * @param matches フォーマット済みの一致行リスト
         */
        GrepResult(String source, List<String> matches) {
            this.source = source;
            this.matches = matches;
        }
    }

    /**
     * Java 実装（Windows-31J 前提）でファイル内検索を行います。
     *
     * @param searchPattern  検索パターン（小文字化して比較されます）
     * @param excludePattern 除外パターン（指定がなければ null）
     * @return フォーマット済みの一致行リスト
     * @throws IOException ファイル読み取り時の入出力エラー
     */
    private List<String> collectMatchesByJavaSjis(String searchPattern, String excludePattern) throws IOException {
        List<String> results = new ArrayList<>();
        String needle = searchPattern.toLowerCase(Locale.ROOT);
        String excludeNeedle = Objects.isNull(excludePattern) ? null : excludePattern.toLowerCase(Locale.ROOT);

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

    /**
     * ripgrep (`rg`) を用いて UTF-8 エンコーディングで高速検索を行います。
     *
     * @param searchPattern  検索パターン（渡されたまま使用されます）
     * @param excludePattern 除外パターン（小文字化して比較されます）
     * @return フォーマット済みの一致行リスト（存在しなければ空リスト）
     */
    private List<String> collectMatchesByRgUtf(String searchPattern, String excludePattern) {
        List<String> results = new ArrayList<>();
        String excludeNeedle = Objects.isNull(excludePattern) ? null : excludePattern.toLowerCase(Locale.ROOT);

        List<String> cmd = new ArrayList<>(Arrays.asList(
                resolvedRgExe,
                "--encoding", "utf-8",
                "--line-number",
                "--no-heading",
                "--color", "never",
                "--fixed-strings",
                "--ignore-case",
                searchPattern,
                ".",
                "--glob", "!build/**",
                "--glob", "!.gradle/**",
                "--glob", "!bin/**"));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(rootDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            boolean finished = process.waitFor(RG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.info("[GREP] rg timed out, fallback to Java grep. command={}", cmd);
                return results;
            }

            int exitCode = process.exitValue();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (exitCode != 0 && exitCode != 1) {
                String summary = output.isBlank() ? "(no output)" : output.lines().findFirst().orElse("(no output)");
                log.info("[GREP] rg failed (exit={}), fallback to Java grep. firstLine={}", exitCode, summary);
                return results;
            }

            if (output.isBlank()) {
                return results;
            }

            String[] lines = output.split("\\R");
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String formatted = formatRgOutputLine(line, excludeNeedle);
                if (Objects.isNull(formatted)) {
                    continue;
                }
                results.add(formatted);
                if (results.size() >= maxMatches) {
                    break;
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.info("[GREP] rg execution error, fallback to Java grep. message={}", e.getMessage());
            return results;
        }

        return results;
    }

    /**
     * ripgrep の出力行をパースして `path:line | text` 形式に整形します。
     *
     * @param rgLine        ripgrep の出力行
     * @param excludeNeedle 除外パターン（小文字化済み、指定がなければ null）
     * @return 整形済み文字列、除外または解析不能な場合は null
     */
    private String formatRgOutputLine(String rgLine, String excludeNeedle) {
        int firstColon = rgLine.indexOf(':');
        if (firstColon <= 0) {
            return null;
        }
        int secondColon = rgLine.indexOf(':', firstColon + 1);
        if (secondColon <= firstColon + 1) {
            return null;
        }

        String pathPart = rgLine.substring(0, firstColon).replace('\\', '/');
        String lineNoPart = rgLine.substring(firstColon + 1, secondColon);
        String textPart = rgLine.substring(secondColon + 1).strip();

        if (excludeNeedle != null && textPart.toLowerCase(Locale.ROOT).contains(excludeNeedle)) {
            return null;
        }
        if (textPart.length() > 120) {
            textPart = textPart.substring(0, 120) + "...";
        }
        return "%s:%s | %s".formatted(pathPart, lineNoPart, textPart);
    }

    /**
     * 実行可能な `rg` コマンドの候補を探索してパスを返します。見つからなければ単に "rg" を返します。
     *
     * @return 利用する `rg` 実行ファイルのパスまたはコマンド名
     */
    private String resolveRgExe() {
        // 1. rootDir/addons（ユーザーのワークスペース内 addons）
        Path addonsExe = rootDir.resolve("addons/rg.exe");
        if (Files.isRegularFile(addonsExe)) {
            return addonsExe.toString();
        }
        Path addonsPlain = rootDir.resolve("addons/rg");
        if (Files.isRegularFile(addonsPlain)) {
            return addonsPlain.toString();
        }
        // 2. JVM 作業ディレクトリ/addons（アプリ自身の addons）
        Path appAddonsExe = Path.of(System.getProperty("user.dir")).resolve("addons/rg.exe");
        if (Files.isRegularFile(appAddonsExe)) {
            return appAddonsExe.toString();
        }
        Path appAddonsPlain = Path.of(System.getProperty("user.dir")).resolve("addons/rg");
        if (Files.isRegularFile(appAddonsPlain)) {
            return appAddonsPlain.toString();
        }
        return "rg";
    }

    /**
     * 指定した `rg` 実行ファイルが実行可能かどうかをチェックします。
     *
     * @param rgExe `rg` 実行ファイルのパスまたはコマンド名
     * @return 実行可能であれば true、そうでなければ false
     */
    private boolean isRgAvailable(String rgExe) {
        try {
            ProcessBuilder pb = new ProcessBuilder(rgExe, "--version");
            pb.directory(rootDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * 単一ファイルを読み込み、検索語に一致する行を結果リストに追加します。
     *
     * @param file          検索対象のファイルパス
     * @param needle        小文字化された検索語
     * @param excludeNeedle 小文字化された除外語（指定がなければ null）
     * @param results       マッチ結果を追加するリスト（変更されます）
     */
    private void collectFileMatches(Path file, String needle, String excludeNeedle, List<String> results) {
        List<String> lines;
        // Java フォールバックは Windows-31J 専用で読む。
        try {
            lines = Files.readAllLines(file, WINDOWS_31J);
        } catch (IOException | RuntimeException e) {
            return;
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lineLower = line.toLowerCase(Locale.ROOT);
            // 検索パターンを含む か つ 除外パターンを含まない行を取得
            if (lineLower.contains(needle)) {
                if (Objects.isNull(excludeNeedle) || !lineLower.contains(excludeNeedle)) {
                    results.add(formatMatch(file, i + 1, line));
                    if (results.size() >= maxMatches) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * ファイルが検索対象かどうかを判定します（サイズ制限・除外ディレクトリチェックなど）。
     *
     * @param file 判定対象のファイルパス
     * @return 検索対象であれば true
     */
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

    /**
     * マッチした行を `path:line | text` 形式に整形します。
     *
     * @param file       マッチ元のファイルパス
     * @param lineNumber マッチした行番号（1始まり）
     * @param line       マッチした行の内容（生テキスト）
     * @return 整形済みの単一行表現
     */
    private String formatMatch(Path file, int lineNumber, String line) {
        String normalized = rootDir.relativize(file).toString().replace('\\', '/');
        String singleLine = line.strip();
        if (singleLine.length() > 120) {
            singleLine = singleLine.substring(0, 120) + "...";
        }
        return normalized + ":%s | ".formatted(lineNumber) + singleLine;
    }
}
