package jp.euks.myagent2.tools;


import java.util.Objects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import jp.euks.myagent2.mcp.McpToolRegistry;
import jp.euks.myagent2.mcpserver.tools.DocumentTextExtractor;
import jp.euks.myagent2.mcpserver.tools.ExcelReaderTool;
import jp.euks.myagent2.mcpserver.tools.FileReaderTool;
import jp.euks.myagent2.mcpserver.tools.GitLogTool;
import jp.euks.myagent2.mcpserver.tools.LocalCommandTool;
import jp.euks.myagent2.mcpserver.tools.WorkspaceGrepTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * `/tool` プレフィックス付きの手動ツールコマンドを解析して実行する既定実装。
 *
 * <p>
 * CLI風の簡易ツール群（time, echo, grep, gitlog, gitshow 等）を提供し、
 * ランタイムに作業ディレクトリを切り替える `setdir`/`getdir` をサポートします。
 * {@link McpToolRegistry} が注入されている場合は、未知のツール名を MCP サーバーに委譲します。
 */
public class DefaultManualToolExecutor implements ManualToolExecutor {
    private static final String PREFIX = "/tool";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Logger log = LoggerFactory.getLogger(DefaultManualToolExecutor.class);

    private final Clock clock;
    private Path currentDir;
    private WorkspaceGrepTool workspaceGrepTool;
    private GitLogTool gitLogTool;
    private LocalCommandTool localCommandTool;
    /** ファイル読み取りツール。 */
    private FileReaderTool fileReaderTool = new FileReaderTool();
    /** Excel 読み取りツール。 */
    private ExcelReaderTool excelReaderTool = new ExcelReaderTool();
    /** バイナリ添付ストア。 */
    private BinaryAttachmentStore binaryAttachmentStore;
    /** MCP ツールレジストリ（null の場合は組み込みツールのみ使用）。 */
    private final McpToolRegistry mcpToolRegistry;

    /**
     * 既定のコンストラクタ。`user.dir` を作業ディレクトリとして使用します。
     */
    public DefaultManualToolExecutor() {
        this(Clock.systemDefaultZone(), Path.of(System.getProperty("user.dir")), null);
    }

    /**
     * MCP レジストリ付きコンストラクタ。`user.dir` を作業ディレクトリとして使用します。
     *
     * @param mcpToolRegistry MCP ツールレジストリ（null 可）
     */
    public DefaultManualToolExecutor(McpToolRegistry mcpToolRegistry) {
        this(Clock.systemDefaultZone(), Path.of(System.getProperty("user.dir")), mcpToolRegistry);
    }

    /**
     * 既定のコンストラクタ。`user.dir` を作業ディレクトリとして使用します。
     * 
     * @param clock 時刻取得用の Clock インスタンス（テスト用に注入可能）
     */
    DefaultManualToolExecutor(Clock clock) {
        this(clock, Path.of(System.getProperty("user.dir")), null);
    }

    /**
     * テスト用に Clock と WorkspaceGrepTool を注入できるコンストラクタ。
     * WorkspaceGrepTool は作業ディレクトリを基点に生成されます。
     * 
     * @param clock             時刻取得用の Clock インスタンス
     * @param workspaceGrepTool grep ツールのインスタンス
     */
    DefaultManualToolExecutor(Clock clock, WorkspaceGrepTool workspaceGrepTool) {
        this(clock, Path.of(System.getProperty("user.dir")), null);
        this.workspaceGrepTool = workspaceGrepTool;
    }

    /**
     * テスト用に Clock、WorkspaceGrepTool、GitLogTool を注入できるコンストラクタ。
     * 
     * @param clock             時刻取得用の Clock インスタンス
     * @param workspaceGrepTool grep ツールのインスタンス
     * @param gitLogTool        git ログツールのインスタンス
     */
    DefaultManualToolExecutor(Clock clock, WorkspaceGrepTool workspaceGrepTool, GitLogTool gitLogTool) {
        this(clock, Path.of(System.getProperty("user.dir")), null);
        this.workspaceGrepTool = workspaceGrepTool;
        this.gitLogTool = gitLogTool;
    }

    /**
     * テスト用に Clock と作業ディレクトリを注入できるコンストラクタ。WorkspaceGrepTool と GitLogTool
     * は指定されたディレクトリを基点に生成されます。
     * 
     * @param clock 時刻取得用の Clock インスタンス
     * @param dir   作業ディレクトリ
     */
    DefaultManualToolExecutor(Clock clock, Path dir) {
        this(clock, dir, null);
    }

    /**
     * MCP レジストリと作業ディレクトリを注入できる基底コンストラクタ。
     *
     * @param clock           時刻取得用の Clock インスタンス
     * @param dir             作業ディレクトリ
     * @param mcpToolRegistry MCP ツールレジストリ（null 可）
     */
    DefaultManualToolExecutor(Clock clock, Path dir, McpToolRegistry mcpToolRegistry) {
        this.clock = clock;
        this.currentDir = dir.toAbsolutePath().normalize();
        this.workspaceGrepTool = new WorkspaceGrepTool(this.currentDir);
        this.gitLogTool = new GitLogTool(this.currentDir);
        this.localCommandTool = new LocalCommandTool(this.currentDir);
        this.fileReaderTool = new FileReaderTool(this.currentDir);
        this.excelReaderTool = new ExcelReaderTool(this.currentDir);
        this.binaryAttachmentStore = new BinaryAttachmentStore(this.currentDir);
        this.mcpToolRegistry = mcpToolRegistry;
    }

    @Override
    /**
     * ユーザー入力が `/tool` コマンドであれば解析・実行して結果を返す。
     * 対応するツールがない、引数が不正な場合はエラーメッセージを返す。
     *
     * @param userMessage ユーザー入力文字列
     * @return ツール出力（`(tool:...)` 形式）または `Optional.empty()`
     */
    public Optional<String> tryExecute(String userMessage) {
        if (Objects.isNull(userMessage)) {
            return Optional.empty();
        }

        String trimmed = userMessage.trim();
        if (!trimmed.startsWith(PREFIX)) {
            return Optional.empty();
        }

        String remainder = trimmed.substring(PREFIX.length()).trim();
        if (remainder.isEmpty() || remainder.equalsIgnoreCase("help")) {
            return Optional.of(helpText());
        }

        String[] parts = remainder.split("\\s+", 2);
        String toolName = parts[0].toLowerCase(Locale.ROOT);
        String args = parts.length > 1 ? parts[1].trim() : "";

        return Optional.of(runTool(toolName, args));
    }

    /**
     * ツール名に基づいて適切なツール実行メソッドを呼び出す内部ルーティング関数。
     * 組み込みメタコマンド（time, echo, setdir, getdir）を最優先で処理し、
     * それ以外は MCP サーバー → 組み込みツールの順で委譲します。
     * 
     * @param toolName ツール名（小文字）
     * @param args     ツール引数（既にトリム済み）
     * @return ツール実行結果文字列
     */
    private String runTool(String toolName, String args) {
        // 組み込みメタコマンドは常にここで処理する
        return switch (toolName) {
            case "time" -> "(tool:time) " + LocalDateTime.now(clock).format(TIME_FORMATTER);
            case "echo" -> runEcho(args);
            case "setdir" -> runSetDir(args);
            case "getdir" -> runGetDir();
            default -> {
                // MCP サーバーに委譲を試みる（設定済みの場合）
                String mcpResult = tryExecuteViaMcp(toolName, args);
                if (mcpResult != null) {
                    yield mcpResult;
                }
                // 組み込みツールへフォールバック
                yield runBuiltinTool(toolName, args);
            }
        };
    }

    /**
     * MCP サーバーへのツール委譲を試みる。
     * サーバーにツールが存在しない場合は {@code null} を返す。
     *
     * @param toolName ツール名
     * @param args     コマンドライン引数文字列
     * @return MCP 実行結果テキスト（プレフィックス付き）、またはツール未検出時は {@code null}
     */
    private String tryExecuteViaMcp(String toolName, String args) {
        if (mcpToolRegistry == null) {
            return null;
        }
        try {
            Map<String, Object> argsMap = buildArgsMap(toolName, args);
            String result = mcpToolRegistry.executeTool(toolName, argsMap);
            if (result != null) {
                return "(tool:%s) %s".formatted(toolName, result);
            }
        } catch (Exception e) {
            log.debug("MCP tool execution failed for '{}', falling back to built-in: {}", toolName, e.getMessage(), e);
        }
        return null;
    }

    /**
     * 組み込みツールの実行ルーティング。
     *
     * @param toolName ツール名（小文字）
     * @param args     ツール引数（既にトリム済み）
     * @return ツール実行結果文字列
     */
    private String runBuiltinTool(String toolName, String args) {
        return switch (toolName) {
            case "grep" -> runGrep(args);
            case "gitlog" -> runGitLog(args);
            case "gitshow" -> gitLogTool.show(args);
            case "gitbranch" -> gitLogTool.branch();
            case "cmd" -> runCmd(args);
            case "readfile" -> runReadFile(args);
            case "readexcel" -> runReadExcel(args);
            default -> "(tool:error) 未知のツールです: %s。`/tool help` を使ってください。".formatted(toolName);
        };
    }

    /**
     * ツール名と引数文字列から MCP に渡す引数マップを構築する。
     * 引数文字列が JSON オブジェクトであればパースし、それ以外はツール別に適切なパラメータ名にマッピングする。
     *
     * @param toolName ツール名
     * @param args     コマンドライン引数文字列
     * @return MCP に渡す引数マップ
     */
    private Map<String, Object> buildArgsMap(String toolName, String args) {
        if (args.isEmpty()) {
            return Collections.emptyMap();
        }
        // JSON オブジェクトとして解析できる場合はそのまま使用
        if (args.startsWith("{") && args.endsWith("}")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new com.google.gson.Gson().fromJson(args, Map.class);
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception e) {
                log.debug("Failed to parse args as JSON for tool '{}', treating as plain string: {}", toolName, e.getMessage());
            }
        }
        
        // ツール別の引数マッピング
        Map<String, Object> argsMap = new LinkedHashMap<>();
        switch (toolName) {
            case "grep" -> argsMap.put("query", args);
            case "gitshow" -> argsMap.put("ref", args);
            case "readfile" -> argsMap.put("path", args);
            case "readexcel" -> {
                java.util.List<String> tokens = splitArgs(args);
                if (tokens.size() >= 1) argsMap.put("path", tokens.get(0));
                if (tokens.size() >= 2) argsMap.put("sheetName", tokens.get(1));
                if (tokens.size() >= 3) argsMap.put("range", tokens.get(2));
            }
            case "writefile" -> {
                java.util.List<String> tokens = splitArgs(args);
                if (tokens.size() >= 1) argsMap.put("path", tokens.get(0));
                if (tokens.size() >= 2) argsMap.put("content", String.join(" ", tokens.subList(1, tokens.size())));
            }
            case "localcmd" -> argsMap.put("command", args);
            case "gitlog" -> {
                // gitlog の引数を解析してマッピング
                String author = extractGitOption(args, "author");
                String after = extractGitOption(args, "after");
                String before = extractGitOption(args, "before");
                String file = args
                        .replaceAll("--author(?:=|\\s+)\\S+", "")
                        .replaceAll("--after(?:=|\\s+)\\S+", "")
                        .replaceAll("--before(?:=|\\s+)\\S+", "")
                        .trim();
                if (!file.isEmpty()) argsMap.put("file", file);
                if (!author.isEmpty()) argsMap.put("author", author);
                if (!after.isEmpty()) argsMap.put("after", after);
                if (!before.isEmpty()) argsMap.put("before", before);
            }
            default -> argsMap.put("input", args);  // フォールバック
        }
        return argsMap;
    }

    /**
     * gitlog コマンドの引数を解析して GitLogTool を呼び出す。
     *
     * <p>
     * 書式:
     * <ul>
     * <li>{@code /tool gitlog} — 全ブランチのグラフ表示</li>
     * <li>{@code /tool gitlog src/Foo.java} — ファイル絞り込み</li>
     * <li>{@code /tool gitlog --author Alice} — 著者絞り込み</li>
     * <li>{@code /tool gitlog --after 2026-04-03} — この日付以降</li>
     * <li>{@code /tool gitlog --before 2026-04-10} — この日付以前</li>
     * <li>{@code /tool gitlog --after 2026-04-03 --before 2026-04-10} — 範囲指定</li>
     * <li>{@code /tool gitlog --author Alice --after 2026-04-01} — 複合指定</li>
     * </ul>
     * </p>
     */
    private String runGitLog(String args) {
        String author = extractGitOption(args, "author");
        String after = extractGitOption(args, "after");
        String before = extractGitOption(args, "before");

        // 認識済みオプションをすべて除去し、残りをファイルとして扱う
        String file = args
                .replaceAll("--author(?:=|\\s+)\\S+", "")
                .replaceAll("--after(?:=|\\s+)\\S+", "")
                .replaceAll("--before(?:=|\\s+)\\S+", "")
                .trim();

        return gitLogTool.log(file, author, after, before);
    }

    /**
     * 指定されたオプション名に対応する値をコマンド引数文字列から抽出して返します。
     *
     * @param args       コマンド引数文字列
     * @param optionName 抽出するオプション名（例: "author", "after"）
     * @return 見つかった値、見つからなければ空文字
     */
    private static String extractGitOption(String args, String optionName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("--%s(?:=|\\s+)(\\S+)".formatted(optionName))
                .matcher(args);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * echo ツールの実装。
     *
     * @param args エコーする文字列
     * @return (tool:echo) プレフィックス付き出力、引数不足時はエラー
     */
    private String runEcho(String args) {
        if (args.isEmpty()) {
            return "(tool:error) echo には文字列が必要です。例: /tool echo hello";
        }
        return "(tool:echo) " + args;
    }

    /**
     * grep ツールの実装ラッパー。
     *
     * @param args 検索語
     * @return WorkspaceGrepTool の出力またはエラーメッセージ
     */
    private String runGrep(String args) {
        if (args.isEmpty()) {
            return "(tool:error) grep には検索語が必要です。例: /tool grep ChatService";
        }
        return workspaceGrepTool.search(args);
    }

    /**
     * ローカルコマンドを実行するラッパー。
     *
     * @param args 実行するコマンドライン文字列
     * @return 実行結果またはエラーメッセージ
     */
    private String runCmd(String args) {
        if (args.isEmpty()) {
            return "(tool:error) cmd にはコマンドが必要です。例: /tool cmd git log -5";
        }
        return localCommandTool.execute(args);
    }

    /**
     * 作業ディレクトリを変更して関連ツールを再初期化します。
     * MCP レジストリが設定されている場合は同時に再接続します。
     *
     * @param args 新しい作業ディレクトリのパス文字列
     * @return 実行結果メッセージまたはエラーメッセージ
     */
    private String runSetDir(String args) {
        if (args.isEmpty()) {
            return "(tool:error) setdir にはパスが必要です。例: /tool setdir C:\\Users\\example\\project";
        }
        Path newDir = Path.of(args);
        if (!Files.isDirectory(newDir)) {
            return "(tool:error) 指定されたパスはディレクトリではありません: %s".formatted(newDir);
        }
        currentDir = newDir.toAbsolutePath().normalize();
        workspaceGrepTool = new WorkspaceGrepTool(currentDir);
        gitLogTool = new GitLogTool(currentDir);
        localCommandTool = new LocalCommandTool(currentDir);
        fileReaderTool = new FileReaderTool(currentDir);
        excelReaderTool = new ExcelReaderTool(currentDir);
        binaryAttachmentStore = new BinaryAttachmentStore(currentDir);
        if (mcpToolRegistry != null) {
            mcpToolRegistry.reload(currentDir);
        }
        return "(tool:setdir) 作業ディレクトリを変更しました: %s".formatted(currentDir);
    }

    /**
     * /tool readfile <path>
     * 指定したテキストファイルの内容を読み込んで返します。
     * 拡張子はホワイトリスト（txt, md, json, java 等）で制限されます。
     *
     * @param args ファイルパス
     * @return ファイル内容またはエラーメッセージ
     */
    private String runReadFile(String args) {
        if (args.isEmpty()) {
            return "(tool:error) readfile にはファイルパスが必要です。例: /tool readfile src/main/java/jp/euks/myagent2/feature/chat/App.java";
        }
        return "(tool:readfile) %s".formatted(fileReaderTool.readFile(args));
    }

    /**
     * Excel 範囲読み取りラッパー。
     *
     * @param args 引数文字列（file sheet range の形式）
     * @return 読み取り結果またはエラーメッセージ
     */
    private String runReadExcel(String args) {
        java.util.List<String> tokens = splitArgs(args);
        if (tokens.size() != 3) {
            return "(tool:error) readexcel には <file> <sheet> <range> が必要です。例: /tool readexcel AAA.xlsx Sheet1 A1:C3";
        }
        return "(tool:readexcel) %s".formatted(excelReaderTool.readRange(tokens.get(0), tokens.get(1), tokens.get(2)));
    }

    /**
     * Office ドキュメントや PDF のテキスト抽出セクションを構築します。
     * 
     * @param path ファイルパス
     * @return 抽出されたテキスト（抽出できない場合は空文字）
     */
    private String buildExtractionSection(String path) {
        if (Objects.isNull(path)) {
            return "";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".xlsx")
                || lower.endsWith(".xlsm")
                || lower.endsWith(".xls")
                || lower.endsWith(".docx")
                || lower.endsWith(".pptx")
                || lower.endsWith(".pdf"))) {
            return "";
        }

        DocumentTextExtractor extractor = new DocumentTextExtractor(currentDir);
        DocumentTextExtractor.ExtractResult result = extractor.extract(path);
        if (!result.success()) {
            return "";
        }
        return result.text();
    }

    /**
     * /tool getdir
     * 現在の作業ディレクトリを表示します。
     */
    private String runGetDir() {
        return "(tool:getdir) 現在の作業ディレクトリ: %s"
                .formatted(Objects.isNull(currentDir) ? "(未設定)" : currentDir.toAbsolutePath().normalize());
    }

    /**
     * 手動ツールのヘルプテキストを返す。
     *
     * @return ヘルプ文字列
     */
    private String helpText() {
        StringBuilder sb = new StringBuilder(
            "(tool:help) 利用可能な手動ツール: time, echo, grep, gitlog, gitshow, gitbranch, cmd, setdir, getdir, readfile, readexcel\n"
                + "  - /tool time\n"
                + "  - /tool echo <text>\n"
                + "  - /tool grep <query>\n"
                + "  - /tool gitlog [file]         ファイル未指定時は全ブランチ・マージ履歴をグラフ表示\n"
                + "  - /tool gitshow <ref> [-- file]\n"
                + "  - /tool gitbranch              ローカル・リモートブランチ一覧\n"
                + "  - /tool cmd <command> [args]   ローカルコマンド実行（git/grep/rg/nkf ほか。nkf は --overwrite も可）\n"
                + "  - /tool setdir <path>          作業ディレクトリを変更する\n"
                + "  - /tool getdir                 現在の作業ディレクトリを表示する\n"
                + "  - /tool readfile <path>        テキストファイルを読み込む\n"
                + "  - /tool readexcel <file> <sheet> <range>  Excel 範囲を読み込む");
        if (mcpToolRegistry != null) {
            List<String> mcpTools = mcpToolRegistry.listToolNames();
            if (!mcpTools.isEmpty()) {
                sb.append("\n\nMCP ツール（外部サーバー提供）:");
                for (String name : mcpTools) {
                    sb.append("\n  - /tool ").append(name);
                }
            }
        }
        return sb.toString();
    }

    /**
     * ダブルクォート対応の簡易引数分割。
     */
    private static java.util.List<String> splitArgs(String args) {
        java.util.List<String> result = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(args);
        while (matcher.find()) {
            String quoted = matcher.group(1);
            result.add(Objects.isNull(quoted) ? matcher.group(2) : quoted);
        }
        return result;
    }
}


