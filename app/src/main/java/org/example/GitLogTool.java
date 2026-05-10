package org.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * git ログ参照用のユーティリティ。
 *
 * <p>読み取り専用で `git log` / `git show` を実行し、その出力を整形して返す。
 * 引数検証・行数制限を行って安全に利用できるように設計されています。
 */
public class GitLogTool {
    private static final int DEFAULT_LOG_ENTRIES = 100;
    /** git log 出力の最大行数（これを超えると省略メッセージを表示）。 */
    private static final int MAX_LOG_LINES = 1000;
    /** git show / diff 出力の最大行数。 */
    private static final int MAX_SHOW_LINES = 1000;
    private static final int MAX_ARG_LENGTH = 200;
    private static final char GRAPH_BACKSLASH_REPLACEMENT = '╲';
    private static final Pattern SAFE_GIT_TOKEN = Pattern.compile("^[A-Za-z0-9._/\\\\~^-]+$");
    /** 著者名として許可するパターン（Unicode文字・数字・空白・記号）。 */
    private static final Pattern SAFE_AUTHOR = Pattern.compile("^[\\p{L}\\p{N} ._@+'-]+$");
    /** 日付として許可するパターン（YYYY-MM-DD または YYYY/MM/DD）。 */
    private static final Pattern SAFE_DATE = Pattern.compile("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}$");

    private final Path workDir;
    private final int maxLogEntries;

    /**
     * 指定ディレクトリを基点に git ログを参照するツールを作成します。
     * ディレクトリが git リポジトリ内であれば自動的にルートを検出し、そうでなければ指定ディレクトリをそのまま使用します。
     * @param workDir 基点となるディレクトリ
     */
    public GitLogTool(Path workDir) {
        this(workDir, DEFAULT_LOG_ENTRIES);
    }

    /**
     * 指定ディレクトリを基点に git ログを参照するツールを作成します。
     * ディレクトリが git リポジトリ内であれば自動的にルートを検出し、そうでなければ指定ディレクトリをそのまま使用します。
     * @param workDir 基点となるディレクトリ
     * @param maxLogEntries 最大ログエントリ数
     */
    GitLogTool(Path workDir, int maxLogEntries) {
        this.workDir = resolveGitRoot(workDir);
        this.maxLogEntries = maxLogEntries;
    }

    /**
     * 指定ディレクトリから git のリポジトリルートを検出して返す。
     * 検出に失敗した場合は元のディレクトリをそのまま使う。
     */
    private static Path resolveGitRoot(Path dir) {
        try {
            Process proc = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                .directory(dir.toFile())
                .start();
            String output = new String(proc.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).strip();
            proc.waitFor();
            if (!output.isBlank()) {
                return Path.of(output);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return dir;
    }

    /**
     * git のログ（最近の N 件）を返す。ファイル・著者・日付範囲で絞り込める。
     *
     * <p>すべての引数が空文字の場合は {@code --all --graph --name-status} で
     * リポジトリ全体の履歴をグラフ表示する。</p>
     *
     * @param file   ファイルパスでの絞り込み（空文字で絞り込みなし）
     * @param author コミット者名での絞り込み（空文字で絞り込みなし、部分一致）
     * @param after  この日付以降のコミットに絞り込む（空文字で絞り込みなし、YYYY-MM-DD）
     * @param before この日付以前のコミットに絞り込む（空文字で絞り込みなし、YYYY-MM-DD）
     * @return {@code (tool:gitlog)} プレフィックス付きの出力、またはエラー文字列
     */
    public String log(String file, String author, String after, String before) {
        if (!file.isEmpty() && !isSafeLogArg(file)) {
            return "(tool:error) gitlog のファイル引数が不正です。英数字と . _ / - のみ使用できます。";
        }
        if (!author.isEmpty() && !isSafeAuthorArg(author)) {
            return "(tool:error) gitlog の --author 引数が不正です。";
        }
        if (!after.isEmpty() && !isSafeDateArg(after)) {
            return "(tool:error) gitlog の --after 引数が不正です。日付は YYYY-MM-DD 形式で指定してください。";
        }
        if (!before.isEmpty() && !isSafeDateArg(before)) {
            return "(tool:error) gitlog の --before 引数が不正です。日付は YYYY-MM-DD 形式で指定してください。";
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("log");
        cmd.add("--date=short");
        cmd.add("-" + maxLogEntries);

        if (!author.isEmpty()) {
            cmd.add("--author=" + author);
        }
        if (!after.isEmpty()) {
            cmd.add("--after=" + after);
        }
        if (!before.isEmpty()) {
            cmd.add("--before=" + before);
        }

        boolean graphMode = file.isEmpty() && author.isEmpty() && after.isEmpty() && before.isEmpty();
        if (graphMode) {
            // すべて未指定: 全ブランチ・マージ履歴をグラフ表示
            cmd.add("--all");
            cmd.add("--graph");
            cmd.add("--pretty=format:%h %ad %d %an | %s");
            cmd.add("--name-status");
        } else if (!file.isEmpty()) {
            // ファイル絞り込みあり
            // git の magic pathspec ":(glob)**/<input>" を使うことで、
            // ファイル名のみ（例: App.java）・部分パス（例: org/example/App.java）の
            // どちらを渡されても git ルートからサブディレクトリを再帰的に探せる
            String fileArg = ":(glob)**/" + file;
            cmd.add("--pretty=format:%h %ad %an | %s");
            cmd.add("--");
            cmd.add(fileArg);
        } else {
            // 著者・日付のみ絞り込み
            cmd.add("--pretty=format:%h %ad %an | %s");
            cmd.add("--name-status");
        }

        return run("(tool:gitlog)", cmd, graphMode, MAX_LOG_LINES);
    }

    /**
     * {@link #log(String, String, String, String)} の後方互換ラッパー。
     *
     * @param file   ファイルパス（空文字でリポジトリ全体）
     * @param author コミット者名（空文字で絞り込みなし）
     * @return {@code (tool:gitlog)} プレフィックス付きの出力、またはエラー文字列
     */
    public String log(String file, String author) {
        return log(file, author, "", "");
    }

    /**
     * {@link #log(String, String, String, String)} の後方互換ラッパー。ファイルのみ指定する場合に使用。
     *
     * @param args ファイルパス（空文字でリポジトリ全体）
     * @return {@code (tool:gitlog)} プレフィックス付きの出力、またはエラー文字列
     */
    public String log(String args) {
        return log(args, "", "", "");
    }

    /**
     * ローカルおよびリモートのブランチ一覧を返す。
     *
     * <p>{@code git branch -a} を実行し、ブランチ名と現在チェックアウト中のブランチ（*）を表示する。</p>
     *
     * @return {@code (tool:gitbranch)} プレフィックス付きの出力、またはエラー文字列
     */
    public String branch() {
        List<String> cmd = List.of("git", "branch", "-a", "-v");
        return run("(tool:gitbranch)", cmd, false, MAX_LOG_LINES);
    }

    /**
     * git show を実行してコミット内容や差分統計を返す（読み取り専用）。
     *
     * <p>引数は空であってはならず、安全な形式であることを検証する。危険な引数は拒否する。</p>
     *
     * @param args git show に渡す引数（例: "HEAD"、または "HEAD -- path"）
     * @return `(tool:gitshow)` プレフィックス付きの出力、またはエラー文字列
     */
    public String show(String args) {
        if (args.isEmpty()) {
            return "(tool:error) gitshow には引数が必要です。例: /tool gitshow HEAD, /tool gitshow HEAD -- App.java";
        }
        if (!isSafeShowArgs(args)) {
            return "(tool:error) gitshow の引数が不正です。安全な ref / path のみ指定できます。";
        }

        List<String> cmd = new ArrayList<>(List.of("git", "show", "--stat", "--format=short"));
        for (String token : args.split("\\s+")) {
            cmd.add(token);
        }

        return run("(tool:gitshow)", cmd, false, MAX_SHOW_LINES);
    }

    private String run(String prefix, List<String> cmd, boolean normalizeGraphBackslash, int maxLines) {
        try {
            Process proc = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();

            String rawOutput = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
            String displayOutput = normalizeGraphBackslash
                ? normalizeGraphOutput(rawOutput)
                : rawOutput;
            int exitCode = proc.waitFor();

            if (exitCode != 0 && displayOutput.isBlank()) {
                return "(tool:error) git コマンドが失敗しました (exit=" + exitCode + ")";
            }

            List<String> lines = List.of(displayOutput.split("\n"));
            int totalLines = lines.size();
            if (totalLines > maxLines) {
                lines = lines.subList(0, maxLines);
                return prefix + "\n" + String.join("\n", lines)
                    + "\n... (省略: " + totalLines + " 行中 " + maxLines + " 行を表示)";
            }

            return prefix + " (" + totalLines + " 行)\n" + displayOutput;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "(tool:error) git 実行中に失敗しました: " + e.getMessage();
        }
    }

    /**
     * Windows日本語環境で '\\' が '￥' に見える問題を回避するため、
     * git graph の斜線を表示用文字へ置換する。
     */
    private String normalizeGraphOutput(String output) {
        return output.replace('\\', GRAPH_BACKSLASH_REPLACEMENT);
    }

    private boolean isSafeLogArg(String arg) {
        String normalized = arg.trim();
        return !normalized.isEmpty()
            && normalized.length() <= MAX_ARG_LENGTH
            && !normalized.startsWith("-")
            && SAFE_GIT_TOKEN.matcher(normalized).matches();
    }

    /**
     * コミット者名として安全な文字列かを検証する。
     * Unicode 文字・数字・空白・ドット・ハイフン・アンダースコア・@ を許可する。
     */
    private boolean isSafeAuthorArg(String author) {
        String normalized = author.trim();
        return !normalized.isEmpty()
            && normalized.length() <= MAX_ARG_LENGTH
            && SAFE_AUTHOR.matcher(normalized).matches();
    }

    /**
     * 日付として安全な文字列かを検証する（YYYY-MM-DD または YYYY/MM/DD）。
     */
    private boolean isSafeDateArg(String date) {
        return SAFE_DATE.matcher(date.trim()).matches();
    }

    private boolean isSafeShowArgs(String args) {
        String normalized = args.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_ARG_LENGTH) {
            return false;
        }

        String[] tokens = normalized.split("\\s+");
        boolean separatorSeen = false;
        for (String token : tokens) {
            if (token.isEmpty()) {
                return false;
            }
            if ("--".equals(token)) {
                if (separatorSeen) {
                    return false;
                }
                separatorSeen = true;
                continue;
            }
            if (token.startsWith("-")) {
                return false;
            }
            if (!SAFE_GIT_TOKEN.matcher(token).matches()) {
                return false;
            }
        }
        return true;
    }
}
