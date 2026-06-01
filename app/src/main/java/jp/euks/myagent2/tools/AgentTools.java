package jp.euks.myagent2.tools;



import java.util.Objects;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * LLM (OpenAI 互換) が利用可能なツール群。
 * 
 * @Tool アノテーション付きメソッドは LangChain4j によって自動的に Function Calling に登録される。
 */
public class AgentTools {
    private WorkspaceGrepTool grepTool;
    private GitLogTool gitTool;
    private FileReaderTool fileReaderTool;
    private FileWriterTool fileWriterTool;
    private LocalCommandTool localCommandTool;
    private ExcelReaderTool excelReaderTool;
    private BinaryAttachmentStore binaryAttachmentStore;
    private final ToolExecutionTracker toolExecutionTracker;

    /**
     * ツール群を全て渡すコンストラクタ。
     * null でも構わない（Tool メソッド内で null チェック）。
     * 
     * @param grepTool       ワークスペース検索ツール（null 可）
     * @param gitTool        git ログ参照ツール（null 可）
     * @param fileReaderTool ファイル読み取りツール（null 可）
     * @param fileWriterTool ファイル書き込みツール（null 可）
     */
    public AgentTools(
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool) {
        this(grepTool, gitTool, fileReaderTool, fileWriterTool, null, null);
    }

    /**
     * コンストラクタ（簡易）。
     *
     * @param grepTool         ワークスペース検索ツール（null 可）
     * @param gitTool          git ログ参照ツール（null 可）
     * @param fileReaderTool   ファイル読み取りツール（null 可）
     * @param fileWriterTool   ファイル書き込みツール（null 可）
     * @param localCommandTool ローカルコマンド実行ツール（null 可）
     */
    public AgentTools(
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool,
            LocalCommandTool localCommandTool) {
        this(grepTool, gitTool, fileReaderTool, fileWriterTool, localCommandTool, null);
    }

    /**
     * コンストラクタ（LocalCommandTool あり）。
     *
     * @param grepTool             ワークスペース検索ツール（null 可）
     * @param gitTool              git ログ参照ツール（null 可）
     * @param fileReaderTool       ファイル読み取りツール（null 可）
     * @param fileWriterTool       ファイル書き込みツール（null 可）
     * @param localCommandTool     ローカルコマンド実行ツール（null 可）
     * @param toolExecutionTracker ツール実行のトラッキング用オブジェクト（null 可）
     */
    public AgentTools(
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool,
            LocalCommandTool localCommandTool,
            ToolExecutionTracker toolExecutionTracker) {
        this(grepTool, gitTool, fileReaderTool, fileWriterTool, localCommandTool, new ExcelReaderTool(), null,
                toolExecutionTracker);
    }

    /**
     * コンストラクタ（tracker あり）。
     *
     * @param grepTool             ワークスペース検索ツール
     * @param gitTool              git ログ参照ツール
     * @param fileReaderTool       ファイル読み取りツール
     * @param fileWriterTool       ファイル書き込みツール
     * @param localCommandTool     ローカルコマンド実行ツール
     * @param toolExecutionTracker ツール実行のトラッキング用オブジェクト（null 可）
     * @param excelReaderTool      Excel 読み取りツール（null 可）
     */
    public AgentTools(
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool,
            LocalCommandTool localCommandTool,
            ExcelReaderTool excelReaderTool,
            ToolExecutionTracker toolExecutionTracker) {
        this(grepTool, gitTool, fileReaderTool, fileWriterTool, localCommandTool, excelReaderTool, null,
                toolExecutionTracker);
    }

    /**
     * ツール群と tracker を全て渡すコンストラクタ（バイナリ添付対応）。
     *
     * @param grepTool              ワークスペース検索ツール（null 可）
     * @param gitTool               git ログ参照ツール（null 可）
     * @param fileReaderTool        ファイル読み取りツール（null 可）
     * @param fileWriterTool        ファイル書き込みツール（null 可）
     * @param localCommandTool      ローカルコマンド実行ツール（null 可）
     * @param excelReaderTool       Excel 読み取りツール（null 可）
     * @param binaryAttachmentStore バイナリ添付ストア（null 可）
     * @param toolExecutionTracker  ツール実行のトラッキング用オブジェクト（null 可）
     */
    public AgentTools(
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool,
            LocalCommandTool localCommandTool,
            ExcelReaderTool excelReaderTool,
            BinaryAttachmentStore binaryAttachmentStore,
            ToolExecutionTracker toolExecutionTracker) {
        this.grepTool = grepTool;
        this.gitTool = gitTool;
        this.fileReaderTool = fileReaderTool;
        this.fileWriterTool = fileWriterTool;
        this.localCommandTool = localCommandTool;
        this.excelReaderTool = excelReaderTool;
        this.binaryAttachmentStore = binaryAttachmentStore;
        this.toolExecutionTracker = toolExecutionTracker;
    }

    /**
     * 全ツールを受け取るフルコンストラクタ。
     * コンストラクタで全てのツールとトラッカーを受け取ります。
     *
     * @param grepTool         ワークスペース検索ツール（null 可）
     * @param gitTool          git ログ参照ツール（null 可）
     * @param localCommandTool ローカルコマンド実行ツール（null 可）
     */
    public void updateToolReferences(WorkspaceGrepTool grepTool, GitLogTool gitTool,
            LocalCommandTool localCommandTool) {
        this.grepTool = grepTool;
        this.gitTool = gitTool;
        this.localCommandTool = localCommandTool;
    }

    /**
     * setdir 連動用にファイルI/Oツール参照を差し替える。
     * 
     * @param fileReaderTool 新しい `FileReaderTool`（null 可）
     * @param fileWriterTool 新しい `FileWriterTool`（null 可）
     */
    public void updateFileToolReferences(jp.euks.myagent2.tools.FileReaderTool fileReaderTool,
            jp.euks.myagent2.tools.FileWriterTool fileWriterTool) {
        this.fileReaderTool = fileReaderTool;
        this.fileWriterTool = fileWriterTool;
    }

    /**
     * Excel 読み取りツール参照を差し替える。
     * 
     * @param excelReaderTool 新しい `ExcelReaderTool`（null 可）
     */
    public void updateExcelToolReference(ExcelReaderTool excelReaderTool) {
        this.excelReaderTool = excelReaderTool;
    }

    /**
     * バイナリ添付ストア参照を差し替える。
     * 
     * @param binaryAttachmentStore 新しい `BinaryAttachmentStore`（null 可）
     */
    public void updateBinaryAttachmentStore(BinaryAttachmentStore binaryAttachmentStore) {
        this.binaryAttachmentStore = binaryAttachmentStore;
    }

    /**
     * 現在の日時を "yyyy-MM-dd HH:mm:ss" 形式で返します。
     * 
     * @return フォーマット済みの現在日時文字列
     */
    @Tool("現在の日時を取得する")
    public String time() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * ワークスペース内のファイルからテキストを検索します。
     * 
     * @param query 検索する文字列（部分一致、大小区別なし）
     * @return フォーマット済み検索結果またはエラー文字列
     */
    @Tool("ワークスペース内のファイルからテキストを検索する")
    public String grep(@P("検索する文字列") String query) {
        if (Objects.isNull(grepTool)) {
            return "(error) grepツールが設定されていません";
        }
        if (query == null || query.isBlank()) {
            return "(error) grep の query が不正です";
        }
        String result = grepTool.search(query);
        if (toolExecutionTracker != null) {
            toolExecutionTracker.record("grep", query, result);
        }
        return result;
    }

    /**
     * git のコミット履歴を取得します。
     * 
     * @param file   ファイルで絞り込む（例: App.java、空文字で全体）
     * @param author 著者で絞り込む（部分一致、空文字で無指定）
     * @param after  この日付以降のコミット（YYYY-MM-DD、空文字で無指定）
     * @param before この日付以前のコミット（YYYY-MM-DD、空文字で無指定）
     * @return フォーマット済みの gitlog 出力またはエラー文字列
     */
    @Tool("git のコミット履歴を取得する。ファイル・著者・日付範囲で絞り込める。全て未指定時は全ブランチ・マージ履歴をグラフ表示する。特定ファイルを変更したコミットを調べたい場合は file にファイル名（例: App.java）を指定する")
    public String gitlog(
            @P("絞り込むファイルパス。ファイル名のみ（例: App.java）でも可。省略可") String file,
            @P("絞り込むコミット者名（省略可、部分一致）") String author,
            @P("この日付以降のコミットに絞り込む（YYYY-MM-DD、省略可）") String after,
            @P("この日付以前のコミットに絞り込む（YYYY-MM-DD、省略可）") String before) {
        if (Objects.isNull(gitTool)) {
            return "(error) gitlogツールが設定されていません";
        }
        file = (Objects.isNull(file)) ? "" : file;
        author = (Objects.isNull(author)) ? "" : author;
        after = (Objects.isNull(after)) ? "" : after;
        before = (Objects.isNull(before)) ? "" : before;

        if (!file.isBlank() && !isSafeGitArg(file)) {
            return "(error) gitlog の file が不正です";
        }

        String result = gitTool.log(file, author, after, before);
        if (toolExecutionTracker != null) {
            String param = (file.isBlank() ? "" : "--file %s ".formatted(file)) +
                    (author.isBlank() ? "" : "--author %s ".formatted(author)) +
                    (after.isBlank() ? "" : "--after %s ".formatted(after)) +
                    (before.isBlank() ? "" : "--before %s ".formatted(before));
            toolExecutionTracker.record("gitlog", param.trim(), result);
        }
        return result;
    }

    /**
     * git のコミット内容を参照します。
     * 
     * @param ref コミット参照（例: HEAD）
     * @return `git show` の出力またはエラー文字列
     */
    @Tool("git のコミット内容を参照する")
    public String gitshow(@P("コミットの ref（例: HEAD）") String ref) {
        if (Objects.isNull(gitTool)) {
            return "(error) gitshowツールが設定されていません";
        }
        if (ref == null || ref.isBlank()) {
            return "(error) gitshow の ref が不正です";
        }
        if (!isSafeGitArg(ref)) {
            return "(error) gitshow の ref が不正です";
        }
        String result = gitTool.show(ref);
        if (toolExecutionTracker != null) {
            toolExecutionTracker.record("gitshow", ref, result);
        }
        return result;
    }

    /**
     * git branch コマンドの結果を返します。
     * 
     * @return ローカルおよびリモートブランチの一覧、またはエラー文字列
     */
    @Tool("ローカルおよびリモートブランチの一覧を取得する")
    public String gitbranch() {
        if (Objects.isNull(gitTool)) {
            return "(error) gitbranchツールが設定されていません";
        }
        String result = gitTool.branch();
        if (toolExecutionTracker != null) {
            toolExecutionTracker.record("gitbranch", "", result);
        }
        return result;
    }

    /**
     * テキストファイルを読み込んで返します。
     *
     * @param path 読み込むファイルのパス（絶対または相対）
     * @return ファイル内容またはエラー文字列
     */
    @Tool("テキストファイルを読み込んで内容を返す（対応拡張子: txt, md, json, java 等）")
    public String readfile(@P("読み込むファイルパス（絶対パスまたは相対パス）") String path) {
        if (Objects.isNull(fileReaderTool)) {
            return "(error) readfileツールが設定されていません";
        }
        if (path == null || path.isBlank()) {
            return "(error) readfile の path が不正です";
        }
        String result = fileReaderTool.readFile(path);
        if (toolExecutionTracker != null) {
            toolExecutionTracker.record("readfile", path, result);
        }
        return result;
    }

    /**
     * Excel の指定シート・セル範囲を読み取ります。
     *
     * @param path      Excel ブックのパス（絶対パスまたは相対パス）
     * @param sheetName シート名
     * @param range     セル範囲（例: A1:C3）
     * @return 抽出結果またはエラー文字列
     */
    @Tool("Excel の指定シート・セル範囲を読み取る（部分抽出専用。ファイル全体要約には readbinary を使う）")
    public String readexcel(
            @P("Excel ブックのパス（絶対パスまたは相対パス）") String path,
            @P("シート名") String sheetName,
            @P("セル範囲。例: A1:C3") String range) {
        if (Objects.isNull(excelReaderTool)) {
            return "(error) readexcelツールが設定されていません";
        }
        if (path == null || path.isBlank()) {
            return "(error) readexcel の path が不正です";
        }
        if (sheetName == null || sheetName.isBlank()) {
            return "(error) readexcel の sheetName が不正です";
        }
        if (range == null || range.isBlank()) {
            return "(error) readexcel の range が不正です";
        }
        String result = excelReaderTool.readRange(path, sheetName, range);
        if (toolExecutionTracker != null) {
            toolExecutionTracker.record("readexcel", path + " %s ".formatted(sheetName) + range, result);
        }
        return result;
    }

    /**
     * バイナリファイルを base64 で返します。Excel や PDF などのファイル全体要約には readbinary を優先してください。
     * readbinary はセキュリティ上の理由から、ファイルパスのバリデーションと許可された拡張子のチェックを行っています。
     *
     * @param path 読み込むバイナリファイルのパス（絶対パスまたは相対パス）
     * @return ファイルのメタ情報と base64 エンコードされた内容、またはエラー文字列
     */
    @Tool("バイナリファイルを base64 で返す（xlsx等のファイル全体要約は readbinary を優先）")
    public String readbinary(@P("読み込むバイナリファイルパス（絶対パスまたは相対パス）") String path) {
        if (Objects.isNull(binaryAttachmentStore)) {
            return "(error) readbinaryツールが設定されていません";
        }
        if (path == null || path.isBlank()) {
            return "(error) readbinary の path が不正です";
        }
        try {
            BinaryAttachmentStore.AttachmentMetadata metadata = binaryAttachmentStore.createAttachment(path);
            String extractionSection = buildExtractionSection(path);
            if (!extractionSection.isEmpty()) {
                String result = "file=%s mime=%s size=%d extracted_text=\"%s\"".formatted(
                        metadata.filename(),
                        metadata.mimeType(),
                        metadata.sizeBytes(),
                        extractionSection.replace("\"", "'"));

                if (toolExecutionTracker != null) {
                    toolExecutionTracker.record("readbinary", path, result);
                }
                return result;
            }

            var base64Opt = binaryAttachmentStore.getBase64(metadata.id());
            if (base64Opt.isEmpty()) {
                return "(error) readbinary の base64 変換に失敗しました";
            }
            String result = "file=%s mime=%s size=%d base64=%s".formatted(
                    metadata.filename(),
                    metadata.mimeType(),
                    metadata.sizeBytes(),
                    base64Opt.get());

            if (toolExecutionTracker != null) {
                toolExecutionTracker.record("readbinary", path, result);
            }
            return result;
        } catch (IllegalArgumentException e) {
            return "(error) " + e.getMessage();
        }
    }

    /**
     * 指定パスが Office または PDF ファイルであればテキスト抽出を行い、抽出テキストを返します。
     * 抽出に失敗した場合や対象外の拡張子であれば空文字を返します。
     *
     * @param path 対象ファイルのパス
     * @return 抽出テキスト（存在しない場合は空文字）
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

        Path base = Path.of(System.getProperty("user.dir"));
        if (binaryAttachmentStore != null && binaryAttachmentStore.baseDir() != null) {
            base = binaryAttachmentStore.baseDir();
        }
        DocumentTextExtractor extractor = new DocumentTextExtractor(base);
        DocumentTextExtractor.ExtractResult result = extractor.extract(path);
        if (!result.success()) {
            return "";
        }
        return result.text();
    }

    /**
     * テキストをワークスペース内のファイルに書き込みます。
     *
     * @param path    保存先の相対パス
     * @param content 書き込む内容
     * @return 実行結果文字列
     */
    @Tool("テキストファイルに内容を保存する（許可拡張子: txt, md, csv, json, log, yaml, yml）。ワークスペースルートからの相対パスを指定すること。")
    public String writefile(
            @P("指定の保存先パス（ワークスペースルートからの相対パス。例: output/result.txt）") String path,
            @P("書き込むテキスト内容") String content) {
        if (Objects.isNull(fileWriterTool)) {
            return "(error) writefileツールが設定されていません";
        }
        if (path == null || path.isBlank()) {
            return "(error) writefile の path が不正です";
        }
        if (Objects.isNull(content) || content.isEmpty()) {
            return "(error) writefile の content がありません";
        }
        if (content.length() > 50_000) {
            return "(error) writefile の content が長すぎます";
        }
        String result = fileWriterTool.writeFile(path, content);
        if (toolExecutionTracker != null) {
            toolExecutionTracker.record("writefile", path, result);
        }
        return result;
    }

    /**
     * ローカルコマンドを実行します（危険な文字やオプションは拒否されます）。
     * 
     * @param command 実行するコマンドライン文字列
     * @return 実行結果（標準出力/エラー）またはエラー文字列
     */
    @Tool("ローカルコマンド（git, grep, rg, nkf, ls, find, cat, head, tail, wc, stat, diff, sort, uniq, cut, tree, basename, dirname, realpath）を実行する。シェルメタ文字は拒否。nkf の `--overwrite` は利用可。タイムアウト: 既定20秒（最大30秒）、出力上限: 1000行/100KB。ファイル名の条件検索は `rg --files . | rg -ie <include1> -e <include2> | rg -v -e <exclude>` 形式を優先。")
    public String localcmd(@P("実行するコマンド（例: git log -10, grep pattern file, rg --files . | rg -ie cmd -e local | rg -v -e test）") String command) {
        if (Objects.isNull(localCommandTool)) {
            return "(error) localcmdツールが設定されていません";
        }
        if (command == null || command.isBlank()) {
            return "(error) localcmd の command が不正です";
        }
        String result = localCommandTool.execute(command);
        if (toolExecutionTracker != null) {
            toolExecutionTracker.record("localcmd", command, result);
        }
        return result;
    }

    /**
     * git 引数の安全性チェック。危険オプション拒否・文字種制限。
     */
    private boolean isSafeGitArg(String arg) {
        return !arg.startsWith("-")
                && arg.matches("^[A-Za-z0-9._/\\\\~^-]+$");
    }

}



