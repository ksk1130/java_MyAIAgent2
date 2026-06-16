package jp.euks.myagent2.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * ローカルコマンド実行用ツール。
 *
 * <p>
 * 許可された読み取り専用コマンド（git, grep, nkf, ls, find, cat など）のみを実行。
 * シェルメタ文字を拒否し、タイムアウト・出力上限・パストラバーサル・機密ファイル保護を設定。
 * Windows + Git Bash 環境では Unix コマンドを Git Bash バイナリから自動解決する。
 */
public class LocalCommandTool {
    private static final Logger log = LoggerFactory.getLogger(LocalCommandTool.class);
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 20;
    private static final int MAX_COMMAND_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_LINES = 1000;
    private static final int MAX_OUTPUT_CHARS = 100_000;
    private static final String ADDONS_DIR_NAME = "addons";
    /**
     * addons 探索の既定ディレクトリ。
     * 起動時の user.dir を基準に固定し、セッション切替で作業ディレクトリが変わっても
     * ツール探索先が変化しないようにする。
     */
    private static final Path DEFAULT_ADDONS_DIR = Path
        .of(System.getProperty("user.dir"))
        .toAbsolutePath()
        .normalize()
        .resolve(ADDONS_DIR_NAME)
        .normalize();

    /** 許可するコマンド（小文字）。 */
    private static final Set<String> ALLOWED_COMMANDS = new HashSet<>(Arrays.asList(
        "git", "grep", "rg", "nkf",
        "ls", "find",
        "cat", "head", "tail",
        "wc", "stat", "diff",
        "sort", "uniq", "cut",
        "tree", "basename", "dirname", "realpath"));

    /** git で許可するサブコマンド（読み取り専用）。 */
    private static final Set<String> ALLOWED_GIT_SUBCOMMANDS = new HashSet<>(
        Arrays.asList(
            "log", "show", "branch", "status", "rev-parse", "diff",
            "remote", "tag", "config", "ls-files", "describe", "reflog"));

    /** シェルメタ文字・危険な記号のパターン（'|' は安全な内部パイプとして別扱い、シングルクオート・ダブルクオートは許可）。 */
    private static final Pattern DANGEROUS_CHARS = Pattern.compile("[&;<>()$`\\\\]");

    /** find コマンドで禁止するオプション（コマンド実行・ファイル削除系）。 */
    private static final Set<String> FIND_FORBIDDEN_OPTIONS = new HashSet<>(Arrays.asList(
        "-exec", "-execdir", "-ok", "-okdir",
        "-delete", "-fprint", "-fprintf", "-fls"));

    /** ファイル内容を読み取るコマンド（パス引数の安全チェック対象）。 */
    private static final Set<String> FILE_READING_COMMANDS = new HashSet<>(Arrays.asList(
        "cat", "head", "tail", "wc", "stat", "diff", "sort", "uniq", "cut", "nkf"));

    /** アクセスを禁止する機密ファイルパターン（大文字小文字無視）。 */
    private static final Pattern SENSITIVE_FILE_PATTERN = Pattern.compile(
        "(?i)(^|[/\\\\])\\.env(rc)?$"
        + "|(?i)\\.(key|pem|pfx|p12|jks|keystore|crt|cer|der)$"
        + "|(?i)(password|passwd|secret|credential|private[_-]key|id_rsa|id_ed25519|id_ecdsa)");

    /**
     * Windows 向けに Git Bash が提供する Unix コマンド群の既知 bin ディレクトリ（優先順）。
     */
    private static final List<Path> GIT_BASH_BIN_CANDIDATES = List.of(
        Path.of("C:/Program Files/Git/usr/bin"),
        Path.of("C:/Program Files (x86)/Git/usr/bin"),
        Path.of("C:/Git/usr/bin"));

    /** 起動時に検出した Git Bash の bin ディレクトリ（未検出の場合は null）。 */
    private static final Path GIT_BASH_BIN_DIR = detectGitBashBinDir();
    private static final Set<String> DIAGNOSTIC_LOGGED_BASE_DIRS = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private final Path baseDir;
    private final Path addonsDir;
    private final int commandTimeoutSeconds;

    /** 起動時に解決したコマンド名→実行ファイルパスのマップ。 */
    private final Map<String, String> resolvedExes;

    /**
     * コンストラクタ。
     *
     * @param baseDir コマンドの実行ディレクトリ（基点）
     */
    public LocalCommandTool(Path baseDir) {
        this(baseDir, DEFAULT_ADDONS_DIR);
    }

    /**
     * コンストラクタ。
     *
     * @param baseDir コマンドの実行ディレクトリ（基点）
     * @param addonsDir 実行ファイル探索用 addons ディレクトリ（null の場合は起動時 user.dir/addons）
     */
    public LocalCommandTool(Path baseDir, Path addonsDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        Path resolvedAddonsDir = (addonsDir == null)
            ? DEFAULT_ADDONS_DIR
            : addonsDir.toAbsolutePath().normalize();
        this.addonsDir = resolvedAddonsDir;
        ensureAddonsDirExists();
        this.resolvedExes = buildResolvedExes();
        this.commandTimeoutSeconds = resolveCommandTimeoutSeconds();
        logStartupDiagnostics();
    }

    /**
     * 起動時診断ログを出力する（同一 baseDir では一度だけ）。
     */
    private void logStartupDiagnostics() {
        String baseKey = baseDir.toString();
        if (!DIAGNOSTIC_LOGGED_BASE_DIRS.add(baseKey)) {
            return;
        }

        log.info("[LOCALCMD] addons path: {}", addonsDir);

        StringBuilder sb = new StringBuilder();
        resolvedExes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            });
        log.info("[LOCALCMD] resolved executables: {}", sb);
    }

    /**
     * 外部ツール配置用の addons ディレクトリを作成する。
     */
    private void ensureAddonsDirExists() {
        try {
            Files.createDirectories(addonsDir);
        } catch (IOException ignored) {
            // 作成できない場合は既存 PATH のみで実行を継続する
        }
    }

    /**
     * localcmd のタイムアウト秒数を解決する。
     * 環境変数 MYAGENT2_CMD_TIMEOUT_SECONDS が有効な数値なら採用し、
     * 安全のため 1〜30 秒に丸める。
     */
    private static int resolveCommandTimeoutSeconds() {
        String raw = System.getenv("MYAGENT2_CMD_TIMEOUT_SECONDS");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_COMMAND_TIMEOUT_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1) {
                return 1;
            }
            return Math.min(parsed, MAX_COMMAND_TIMEOUT_SECONDS);
        } catch (NumberFormatException ignored) {
            return DEFAULT_COMMAND_TIMEOUT_SECONDS;
        }
    }

    /**
     * 起動時に各コマンドの実行ファイルパスを解決してマップとして返す。
     * grep/rg は PATH チェックを優先し、それ以外の Unix コマンドは Git Bash から解決する。
     */
    private Map<String, String> buildResolvedExes() {
        Map<String, String> exes = new HashMap<>();
        // grep は PATH チェックを優先（WSL/MSYS2 など環境の grep を使う可能性があるため）
        exes.put("grep", resolveAddonOrDefaultExe("grep", resolveGrepExe()));
        // rg も PATH チェックを優先
        exes.put("rg", resolveAddonOrDefaultExe("rg", resolveRgExe()));
        // nkf は addons を優先し、なければ PATH 解決に委譲
        exes.put("nkf", resolveAddonOrDefaultExe("nkf", "nkf"));
        // その他の Unix コマンドは Git Bash から解決
        for (String cmd : Arrays.asList(
                "ls", "find", "cat", "head", "tail",
                "wc", "stat", "diff", "sort", "uniq", "cut",
                "tree", "basename", "dirname", "realpath")) {
            exes.put(cmd, resolveAddonOrDefaultExe(cmd, resolveUnixExe(cmd)));
        }
        return Collections.unmodifiableMap(exes);
    }

    /**
     * addons 配下に実行ファイルがあれば優先し、なければ既定解決結果を返す。
     */
    private String resolveAddonOrDefaultExe(String command, String defaultResolvedExe) {
        Path exeCandidate = addonsDir.resolve(command + ".exe");
        if (Files.isRegularFile(exeCandidate)) {
            return exeCandidate.toString();
        }
        Path plainCandidate = addonsDir.resolve(command);
        if (Files.isRegularFile(plainCandidate)) {
            return plainCandidate.toString();
        }
        return defaultResolvedExe;
    }

    /**
     * Git Bash の bin ディレクトリを検出する。
     */
    private static Path detectGitBashBinDir() {
        for (Path dir : GIT_BASH_BIN_CANDIDATES) {
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        return null;
    }

    /**
     * Unix コマンドの実行ファイルパスを返す。
     * Git Bash の bin ディレクトリが検出済みであればその絶対パスを、
     * 未検出の場合はコマンド名のみを返す（PATH 任せ）。
     */
    private static String resolveUnixExe(String name) {
        if (GIT_BASH_BIN_DIR != null) {
            Path candidate = GIT_BASH_BIN_DIR.resolve(name + ".exe");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return name;
    }

    /**
     * grep 実行ファイルのパスを解決する。
     * PATH に grep があればそれを優先し、なければ Git Bash の既知パスを探す。
     */
    private static String resolveGrepExe() {
        // PATH に grep があればそのまま使う
        try {
            ProcessBuilder pb = new ProcessBuilder("grep", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                return "grep";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // grep が PATH にないだけなので無視して次の解決方法へ
        }

        // Git Bash から解決
        return resolveUnixExe("grep");
    }

    /**
     * rg 実行ファイルのパスを解決する。
     * PATH に rg があればそれを優先し、なければ Git Bash の既知パスを探す。
     */
    private static String resolveRgExe() {
        // PATH に rg があればそのまま使う
        try {
            ProcessBuilder pb = new ProcessBuilder("rg", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                return "rg";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // rg が PATH にないだけなので無視して次の解決方法へ
        }

        // Git Bash から解決
        return resolveUnixExe("rg");
    }

    /**
     * ローカルコマンドを実行する。
     * 
     * <p>
     * 書式: {@code git log --oneline -10} など。
     * </p>
     * 
     * @param command 実行するコマンド（コマンド + 引数の空白区切り文字列）
     * @return 実行結果（出力またはエラーメッセージ）
     */
    public String execute(String command) {
        if (command == null || command.isBlank()) {
            return "(error) cmd の引数が空です。例: /tool cmd git log -5";
        }

        String trimmed = command.trim();
        if (hasDangerousChars(trimmed)) {
            return "(error) 危険な記号が含まれています: & ; < > ( ) $ ` \\";
        }

        String[] stages = trimmed.split("\\|");
        List<String[]> pipeline = new ArrayList<>();
        for (String stage : stages) {
            String stageTrimmed = stage.trim();
            if (stageTrimmed.isEmpty()) {
                return "(error) パイプの前後にコマンドが必要です";
            }

            List<String> partsList = tokenize(stageTrimmed);
            if (partsList == null) {
                return "(error) 引用符が閉じられていません";
            }
            if (partsList.isEmpty()) {
                return "(error) コマンドが指定されていません";
            }

            String[] parts = partsList.toArray(new String[0]);

            String validationError = validateParts(parts);
            if (validationError != null) {
                return validationError;
            }

            String baseCommand = parts[0].toLowerCase();
            parts[0] = resolvedExes.getOrDefault(baseCommand, baseCommand);
            pipeline.add(parts);
        }

        String result = runPipeline(pipeline);
        return "$ " + trimmed + "\n" + result;
    }

    /**
     * 解決済みの grep 実行ファイルパスを返す（診断用）。
     *
     * @return 解決済みのパス文字列（例: "grep" または 絶対パス）
     */
    public String getResolvedGrepExe() {
        return resolvedExes.getOrDefault("grep", "grep");
    }

    /**
     * 指定コマンドの解決済み実行ファイルパスを返す（診断用）。
     *
     * @param command コマンド名（小文字）
     * @return 解決済みのパス文字列
     */
    public String getResolvedExe(String command) {
        return resolvedExes.getOrDefault(command, command);
    }

    /**
     * 危険文字を含んでいないか検証する。
     */
    private boolean hasDangerousChars(String input) {
        return DANGEROUS_CHARS.matcher(input).find();
    }

    /**
     * コマンド文字列をトークンに分割する。
     * スペースで区切るが、ダブルクオートで囲まれた部分は 1 トークンとして扱う。
     * ダブルクオート自体は除去される。
     * 未閉じのダブルクオートが検出された場合は null を返す。
     * 正常な場合は List<String> を返す。
     *
     * @param input トークン化するコマンド文字列
     * @return トークンリスト、または未閉じ引用符の場合は null
     */
    private List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString());
                    currentToken = new StringBuilder();
                }
            } else {
                currentToken.append(c);
            }
            i++;
        }
        if (inQuotes) {
            return null;  // 未閉じ引用符
        }
        if (currentToken.length() > 0) {
            tokens.add(currentToken.toString());
        }
        return tokens;
    }

    /**
     * 1コマンド分の引数を安全性ルールで検証する。
     */
    private String validateParts(String[] parts) {
        String baseCommand = parts[0].toLowerCase();
        if (!ALLOWED_COMMANDS.contains(baseCommand)) {
            return "(error) 許可されていないコマンドです: " + baseCommand
                + "。許可: " + String.join(", ", ALLOWED_COMMANDS);
        }

        // git の場合、サブコマンドをチェック
        if ("git".equals(baseCommand)) {
            if (parts.length < 2) {
                return "(error) git にはサブコマンドが必要です。例: git log";
            }
            String subcommand = parts[1].toLowerCase();
            if (!ALLOWED_GIT_SUBCOMMANDS.contains(subcommand)) {
                return "(error) git の許可されていないサブコマンドです: " + subcommand
                    + "。許可: " + String.join(", ", ALLOWED_GIT_SUBCOMMANDS);
            }
        }

        // find の場合、危険オプションをチェック
        if ("find".equals(baseCommand)) {
            for (String part : parts) {
                if (FIND_FORBIDDEN_OPTIONS.contains(part.toLowerCase())) {
                    return "(error) find の禁止オプションです: " + part
                        + "（コマンド実行・ファイル削除系オプションは使用不可）";
                }
            }
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].startsWith("-") && parts[i].contains("..")) {
                    return "(error) パストラバーサルが含まれています: " + parts[i];
                }
            }
        }

        // ファイル読み取り系コマンドの引数安全チェック
        if (FILE_READING_COMMANDS.contains(baseCommand)) {
            for (int i = 1; i < parts.length; i++) {
                String arg = parts[i];
                if (arg.startsWith("-")) {
                    continue; // オプション引数はスキップ
                }
                if (arg.contains("..")) {
                    return "(error) パストラバーサルが含まれています: " + arg;
                }
                if (arg.startsWith("/") || arg.matches("[A-Za-z]:.*")) {
                    return "(error) 絶対パスは使用不可です。作業ディレクトリ相対パスを指定してください: " + arg;
                }
                if (SENSITIVE_FILE_PATTERN.matcher(arg).find()) {
                    return "(error) 機密ファイルへのアクセスは禁止されています: " + arg;
                }
            }
        }

        return null;
    }

    /**
     * パイプラインをシェルを介さず順次実行する。
     */
    private String runPipeline(List<String[]> commands) {
        byte[] inputBytes = null;
        String output = "";

        for (String[] parts : commands) {
            CommandResult result = runSingleCommand(parts, inputBytes);
            if (!result.finished) {
                return "(error) コマンドがタイムアウトしました（" + commandTimeoutSeconds + "秒）";
            }

            output = result.output;
            inputBytes = output.getBytes(StandardCharsets.UTF_8);

            if (result.exitCode != 0) {
                return "(tool:cmd) [終了コード: " + result.exitCode + "]\n" + output;
            }
        }

        // 出力行数チェック
        String[] lines = output.split("\\n");
        if (lines.length > MAX_OUTPUT_LINES) {
            output = String.join("\n", Arrays.copyOf(lines, MAX_OUTPUT_LINES))
                + "\n... (省略: 出力行数が上限を超過)";
        }

        // 出力文字数チェック
        if (output.length() > MAX_OUTPUT_CHARS) {
            output = output.substring(0, MAX_OUTPUT_CHARS) + "\n... (省略: 出力文字数が上限を超過)";
        }

        return "(tool:cmd)\n" + output;
    }

    /**
     * コマンドを ProcessBuilder で実行する。
     */
    private CommandResult runSingleCommand(String[] parts, byte[] stdin) {
        try {
            ProcessBuilder pb = new ProcessBuilder(parts);
            pb.directory(baseDir.toFile());
            pb.redirectErrorStream(true);
            prependAddonsToPath(pb.environment());

            Process process = pb.start();

            if (stdin != null && stdin.length > 0) {
                process.getOutputStream().write(stdin);
            }
            process.getOutputStream().close();

            // タイムアウト待機
            // 出力を並行して読み取ることで、出力バッファ詰まりによるデッドロックを防ぐ
            java.io.InputStream is = process.getInputStream();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int read;
                    int total = 0;
                    while ((read = is.read(buf)) != -1) {
                        int toWrite = read;
                        // enforce MAX_OUTPUT_CHARS guard to avoid OOM
                        if (baos.size() + toWrite > MAX_OUTPUT_CHARS) {
                            toWrite = Math.max(0, MAX_OUTPUT_CHARS - baos.size());
                        }
                        if (toWrite > 0) {
                            baos.write(buf, 0, toWrite);
                        }
                        total += read;
                        if (baos.size() >= MAX_OUTPUT_CHARS) {
                            // keep reading the stream but discard beyond limit to avoid blocking; continue to drain
                            // we still continue loop to fully consume stream
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "localcmd-output-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                try { reader.join(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return new CommandResult(false, 124, "");
            }

            try { reader.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            String output = new String(baos.toByteArray(), StandardCharsets.UTF_8).stripTrailing();
            int exitCode = process.exitValue();
            return new CommandResult(true, exitCode, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(true, 130, "(error) コマンド実行がインタラプトされました");
        } catch (IOException e) {
            return new CommandResult(true, 1, "(error) コマンド実行に失敗しました: " + e.getMessage());
        }
    }

    /**
     * 子プロセスの PATH に addons ディレクトリを先頭追加する。
     */
    private void prependAddonsToPath(Map<String, String> env) {
        if (!Files.isDirectory(addonsDir)) {
            return;
        }

        String pathKey = null;
        for (String key : env.keySet()) {
            if ("PATH".equalsIgnoreCase(key)) {
                pathKey = key;
                break;
            }
        }
        if (pathKey == null) {
            pathKey = "PATH";
        }

        String currentPath = env.getOrDefault(pathKey, "");
        String addonsPath = addonsDir.toString();
        if (currentPath.isBlank()) {
            env.put(pathKey, addonsPath);
            return;
        }

        String separator = currentPath.contains(";") ? ";" : java.io.File.pathSeparator;
        for (String part : currentPath.split(Pattern.quote(separator))) {
            if (addonsPath.equalsIgnoreCase(part)) {
                return;
            }
        }
        env.put(pathKey, addonsPath + separator + currentPath);
    }

    /** コマンド実行結果の内部表現。 */
    private static final class CommandResult {
        private final boolean finished;
        private final int exitCode;
        private final String output;

        private CommandResult(boolean finished, int exitCode, String output) {
            this.finished = finished;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
