package jp.euks.myagent2.mcpserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import jp.euks.myagent2.tools.ExcelReaderTool;
import jp.euks.myagent2.tools.FileReaderTool;
import jp.euks.myagent2.tools.FileWriterTool;
import jp.euks.myagent2.tools.GitLogTool;
import jp.euks.myagent2.tools.LocalCommandTool;
import jp.euks.myagent2.tools.WorkspaceGrepTool;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * MCP ツールのディスパッチャー。
 *
 * <p>
 * ツール名から対応する実装クラスへの呼び出しを委譲する。
 * 各ツールは既存の {@code jp.euks.myagent2.tools} パッケージのクラスを再利用する。
 * </p>
 *
 * <h2>公開ツール一覧</h2>
 * <ul>
 *   <li>{@code time} — 現在日時取得</li>
 *   <li>{@code grep} — ワークスペース内テキスト検索</li>
 *   <li>{@code gitlog} — git コミット履歴取得</li>
 *   <li>{@code gitshow} — git コミット内容参照</li>
 *   <li>{@code gitbranch} — ブランチ一覧取得</li>
 *   <li>{@code readfile} — テキストファイル読み込み</li>
 *   <li>{@code readexcel} — Excel セル範囲読み取り</li>
 *   <li>{@code writefile} — テキストファイル書き込み</li>
 *   <li>{@code localcmd} — ローカルコマンド実行</li>
 * </ul>
 */
public class ToolDispatcher {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkspaceGrepTool grepTool;
    private final GitLogTool gitLogTool;
    private final FileReaderTool fileReaderTool;
    private final FileWriterTool fileWriterTool;
    private final LocalCommandTool localCommandTool;
    private final ExcelReaderTool excelReaderTool;

    /**
     * 指定した作業ディレクトリで各ツールを初期化する。
     *
     * @param workDir 作業ディレクトリ（ツールの基点パス）
     */
    public ToolDispatcher(Path workDir) {
        Path resolvedDir = Objects.requireNonNullElse(workDir,
                Path.of(System.getProperty("user.dir"))).toAbsolutePath().normalize();

        this.grepTool = new WorkspaceGrepTool(resolvedDir);
        this.gitLogTool = new GitLogTool(resolvedDir);
        this.fileReaderTool = new FileReaderTool(resolvedDir);
        this.fileWriterTool = new FileWriterTool(resolvedDir);
        this.localCommandTool = new LocalCommandTool(resolvedDir);
        this.excelReaderTool = new ExcelReaderTool(resolvedDir);
    }

    // ------------------------------------------------------------------
    // ToolResult
    // ------------------------------------------------------------------

    /**
     * ツール実行結果。
     *
     * @param text    結果テキスト
     * @param isError エラーの場合 {@code true}
     */
    public record ToolResult(String text, boolean isError) {
        /** 正常結果を生成する。 */
        static ToolResult ok(String text) {
            return new ToolResult(text, false);
        }

        /** エラー結果を生成する。 */
        static ToolResult error(String text) {
            return new ToolResult(text, true);
        }
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    /**
     * ツール名と引数を受け取り、対応するツールを実行して結果を返す。
     *
     * @param toolName  ツール名
     * @param arguments JSON オブジェクト形式の引数
     * @return 実行結果
     */
    public ToolResult execute(String toolName, JsonObject arguments) {
        return switch (toolName) {
            case "time" -> executeTime();
            case "grep" -> executeGrep(arguments);
            case "gitlog" -> executeGitlog(arguments);
            case "gitshow" -> executeGitshow(arguments);
            case "gitbranch" -> executeGitbranch();
            case "readfile" -> executeReadfile(arguments);
            case "readexcel" -> executeReadexcel(arguments);
            case "writefile" -> executeWritefile(arguments);
            case "localcmd" -> executeLocalcmd(arguments);
            default -> ToolResult.error("Unknown tool: " + toolName);
        };
    }

    // ------------------------------------------------------------------
    // Tool implementations
    // ------------------------------------------------------------------

    private ToolResult executeTime() {
        return ToolResult.ok(LocalDateTime.now().format(TIME_FORMATTER));
    }

    private ToolResult executeGrep(JsonObject args) {
        String query = getString(args, "query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("(error) grep の query が必要です");
        }
        String result = grepTool.search(query);
        return wrapResult(result);
    }

    private ToolResult executeGitlog(JsonObject args) {
        String file = getStringOrEmpty(args, "file");
        String author = getStringOrEmpty(args, "author");
        String after = getStringOrEmpty(args, "after");
        String before = getStringOrEmpty(args, "before");
        String result = gitLogTool.log(file, author, after, before);
        return wrapResult(result);
    }

    private ToolResult executeGitshow(JsonObject args) {
        String ref = getString(args, "ref");
        if (ref == null || ref.isBlank()) {
            return ToolResult.error("(error) gitshow の ref が必要です");
        }
        String result = gitLogTool.show(ref);
        return wrapResult(result);
    }

    private ToolResult executeGitbranch() {
        String result = gitLogTool.branch();
        return wrapResult(result);
    }

    private ToolResult executeReadfile(JsonObject args) {
        String path = getString(args, "path");
        if (path == null || path.isBlank()) {
            return ToolResult.error("(error) readfile の path が必要です");
        }
        String result = fileReaderTool.readFile(path);
        return wrapResult(result);
    }

    private ToolResult executeReadexcel(JsonObject args) {
        String path = getString(args, "path");
        String sheetName = getString(args, "sheetName");
        String range = getString(args, "range");
        if (path == null || path.isBlank()) {
            return ToolResult.error("(error) readexcel の path が必要です");
        }
        if (sheetName == null || sheetName.isBlank()) {
            return ToolResult.error("(error) readexcel の sheetName が必要です");
        }
        if (range == null || range.isBlank()) {
            return ToolResult.error("(error) readexcel の range が必要です");
        }
        String result = excelReaderTool.readRange(path, sheetName, range);
        return wrapResult(result);
    }

    private ToolResult executeWritefile(JsonObject args) {
        String path = getString(args, "path");
        String content = getString(args, "content");
        if (path == null || path.isBlank()) {
            return ToolResult.error("(error) writefile の path が必要です");
        }
        if (content == null || content.isEmpty()) {
            return ToolResult.error("(error) writefile の content がありません");
        }
        if (content.length() > 50_000) {
            return ToolResult.error("(error) writefile の content が長すぎます（最大 50000 文字）");
        }
        String result = fileWriterTool.writeFile(path, content);
        return wrapResult(result);
    }

    private ToolResult executeLocalcmd(JsonObject args) {
        String command = getString(args, "command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("(error) localcmd の command が必要です");
        }
        String result = localCommandTool.execute(command);
        return wrapResult(result);
    }

    // ------------------------------------------------------------------
    // JSON argument helpers
    // ------------------------------------------------------------------

    private static String getString(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static String getStringOrEmpty(JsonObject obj, String key) {
        String v = getString(obj, key);
        return v != null ? v : "";
    }

    /**
     * ツール実行結果文字列を {@link ToolResult} に変換する。
     * エラーを示すプレフィックス（{@code (error)} / {@code (tool:error)} / {@code ERROR:}）が
     * 含まれる場合はエラー結果とする。
     */
    private static ToolResult wrapResult(String result) {
        if (result == null) {
            return ToolResult.error("(error) ツールが null を返しました");
        }
        boolean isError = result.startsWith("(error)")
                || result.startsWith("(tool:error)")
                || result.startsWith("ERROR:");
        return new ToolResult(result, isError);
    }

    // ------------------------------------------------------------------
    // Tool schema (tools/list response)
    // ------------------------------------------------------------------

    /**
     * {@code tools/list} レスポンス用のツールスキーマ JSON 配列を返す。
     *
     * @return ToolSpecification の JSON 配列
     */
    public JsonArray getToolsSchema() {
        JsonArray tools = new JsonArray();
        tools.add(buildTool("time",
                "現在の日時を 'yyyy-MM-dd HH:mm:ss' 形式で返す",
                new String[0], new String[0], new String[0]));
        tools.add(buildTool("grep",
                "ワークスペース内のファイルからテキストを検索する（部分一致・大小区別なし）",
                new String[]{"query"},
                new String[]{"string"},
                new String[]{"検索する文字列"}));
        tools.add(buildTool("gitlog",
                "git のコミット履歴を取得する。ファイル・著者・日付範囲で絞り込める。全て未指定時は全ブランチ・マージ履歴をグラフ表示する",
                new String[]{"file", "author", "after", "before"},
                new String[]{"string", "string", "string", "string"},
                new String[]{
                    "絞り込むファイルパス（省略可、例: App.java）",
                    "絞り込むコミット者名（省略可、部分一致）",
                    "この日付以降のコミットに絞り込む（YYYY-MM-DD、省略可）",
                    "この日付以前のコミットに絞り込む（YYYY-MM-DD、省略可）"
                }));
        tools.add(buildTool("gitshow",
                "git のコミット内容（差分・統計）を参照する",
                new String[]{"ref"},
                new String[]{"string"},
                new String[]{"コミットの ref（例: HEAD、コミットハッシュ）"}));
        tools.add(buildTool("gitbranch",
                "ローカルおよびリモートブランチの一覧を取得する",
                new String[0], new String[0], new String[0]));
        tools.add(buildTool("readfile",
                "テキストファイルを読み込んで内容を返す（対応拡張子: txt, md, json, java 等）",
                new String[]{"path"},
                new String[]{"string"},
                new String[]{"読み込むファイルパス（絶対パスまたは相対パス）"}));
        tools.add(buildTool("readexcel",
                "Excel の指定シート・セル範囲を読み取る",
                new String[]{"path", "sheetName", "range"},
                new String[]{"string", "string", "string"},
                new String[]{
                    "Excel ブックのパス（絶対パスまたは相対パス）",
                    "シート名",
                    "セル範囲（例: A1:C3）"
                }));
        tools.add(buildTool("writefile",
                "テキストファイルに内容を保存する（許可拡張子: txt, md, csv, json, log, yaml, yml）。作業ディレクトリからの相対パスを指定すること",
                new String[]{"path", "content"},
                new String[]{"string", "string"},
                new String[]{
                    "保存先パス（作業ディレクトリからの相対パス。例: output/result.txt）",
                    "書き込むテキスト内容"
                }));
        tools.add(buildTool("localcmd",
                "ローカルコマンド（git, grep, rg, nkf, ls, find, cat, head, tail, wc, stat, diff, sort, uniq, cut, tree 等）を実行する。シェルメタ文字は拒否",
                new String[]{"command"},
                new String[]{"string"},
                new String[]{"実行するコマンド（例: git log -10, grep pattern file）"}));
        return tools;
    }

    /**
     * ToolSpecification の JSON オブジェクトを構築する。
     *
     * @param name         ツール名
     * @param description  ツールの説明
     * @param paramNames   パラメータ名の配列
     * @param paramTypes   パラメータ型の配列（"string" 等）
     * @param paramDescs   パラメータ説明の配列
     * @return ToolSpecification JSON オブジェクト
     */
    private static JsonObject buildTool(
            String name,
            String description,
            String[] paramNames,
            String[] paramTypes,
            String[] paramDescs) {

        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();

        for (int i = 0; i < paramNames.length; i++) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", paramTypes[i]);
            if (i < paramDescs.length) {
                prop.addProperty("description", paramDescs[i]);
            }
            properties.add(paramNames[i], prop);
            required.add(paramNames[i]);
        }

        inputSchema.add("properties", properties);
        // gitlog のパラメータはすべて省略可のため required を空にする
        if (!"gitlog".equals(name)) {
            inputSchema.add("required", required);
        } else {
            inputSchema.add("required", new JsonArray());
        }

        tool.add("inputSchema", inputSchema);
        return tool;
    }
}
