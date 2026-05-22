package jp.euks.myagent2.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * LLM (OpenAI 互換) が利用可能なツール群。
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
     */
    public AgentTools(
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool) {
        this(grepTool, gitTool, fileReaderTool, fileWriterTool, null, null);
    }

    /**
     * ツール群を全て渡すコンストラクタ（LocalCommandTool 付き）。
     * null でも構わない（Tool メソッド内で null チェック）。
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
     * ツール群と tracker を全て渡すコンストラクタ。
     * null でも構わない（Tool メソッド内で null チェック）。
     */
    public AgentTools(
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool,
            LocalCommandTool localCommandTool,
            ToolExecutionTracker toolExecutionTracker) {
        this(grepTool, gitTool, fileReaderTool, fileWriterTool, localCommandTool, new ExcelReaderTool(), null, toolExecutionTracker);
    }

    /**
     * ツール群と tracker を全て渡すコンストラクタ（Excel 対応）。
     * null でも構わない（Tool メソッド内で null チェック）。
     */
    public AgentTools(
            WorkspaceGrepTool grepTool,
            GitLogTool gitTool,
            FileReaderTool fileReaderTool,
            FileWriterTool fileWriterTool,
            LocalCommandTool localCommandTool,
            ExcelReaderTool excelReaderTool,
            ToolExecutionTracker toolExecutionTracker) {
        this(grepTool, gitTool, fileReaderTool, fileWriterTool, localCommandTool, excelReaderTool, null, toolExecutionTracker);
    }

    /**
     * ツール群と tracker を全て渡すコンストラクタ（バイナリ添付対応）。
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
     * setdir 連動用にツール参照を差し替える。
     */
    public void updateToolReferences(WorkspaceGrepTool grepTool, GitLogTool gitTool, LocalCommandTool localCommandTool) {
        this.grepTool = grepTool;
        this.gitTool = gitTool;
        this.localCommandTool = localCommandTool;
    }

    /**
     * setdir 連動用にファイルI/Oツール参照を差し替える。
     */
    public void updateFileToolReferences(jp.euks.myagent2.tools.FileReaderTool fileReaderTool, jp.euks.myagent2.tools.FileWriterTool fileWriterTool) {
        this.fileReaderTool = fileReaderTool;
        this.fileWriterTool = fileWriterTool;
    }

    /**
     * Excel 読み取りツール参照を差し替える。
     */
    public void updateExcelToolReference(ExcelReaderTool excelReaderTool) {
        this.excelReaderTool = excelReaderTool;
    }

    /**
     * バイナリ添付ストア参照を差し替える。
     */
    public void updateBinaryAttachmentStore(BinaryAttachmentStore binaryAttachmentStore) {
        this.binaryAttachmentStore = binaryAttachmentStore;
    }

    @Tool("現在の日時を取得する")
    public String time() {
        return LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool("ワークスペース内のファイルからテキストを検索する")
    public String grep(@P("検索する文字列") String query) {
        if (grepTool == null) {
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

    @Tool("git のコミット履歴を取得する。ファイル・著者・日付範囲で絞り込める。全て未指定時は全ブランチ・マージ履歴をグラフ表示する。特定ファイルを変更したコミットを調べたい場合は file にファイル名（例: App.java）を指定する")
    public String gitlog(
            @P("絞り込むファイルパス。ファイル名のみ（例: App.java）でも可。省略可") String file,
            @P("絞り込むコミット者名（省略可、部分一致）") String author,
            @P("この日付以降のコミットに絞り込む（YYYY-MM-DD、省略可）") String after,
            @P("この日付以前のコミットに絞り込む（YYYY-MM-DD、省略可）") String before) {
        if (gitTool == null) {
            return "(error) gitlogツールが設定されていません";
        }
        file = (file == null) ? "" : file;
        author = (author == null) ? "" : author;
        after = (after == null) ? "" : after;
        before = (before == null) ? "" : before;

        if (!file.isBlank() && !isSafeGitArg(file)) {
            return "(error) gitlog の file が不正です";
        }

        String result = gitTool.log(file, author, after, before);
        if (toolExecutionTracker != null) {
            String param = (file.isBlank() ? "" : file + " ") + 
                           (author.isBlank() ? "" : "--author " + author + " ") + 
                           (after.isBlank() ? "" : "--after " + after + " ") + 
                           (before.isBlank() ? "" : "--before " + before);
            toolExecutionTracker.record("gitlog", param.trim(), result);
        }
        return result;
    }

    @Tool("git のコミット内容を参照する")
    public String gitshow(@P("コミットの ref（例: HEAD）") String ref) {
        if (gitTool == null) {
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

    @Tool("ローカルおよびリモートブランチの一覧を取得する")
    public String gitbranch() {
        if (gitTool == null) {
            return "(error) gitbranchツールが設定されていません";
        }
        String result = gitTool.branch();
        if (toolExecutionTracker != null) {
            toolExecutionTracker.record("gitbranch", "", result);
        }
        return result;
    }

    @Tool("テキストファイルを読み込んで内容を返す（対応拡張子: txt, md, json, java 等）")
    public String readfile(@P("読み込むファイルパス（絶対パスまたは相対パス）") String path) {
        if (fileReaderTool == null) {
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

    @Tool("Excel の指定シート・セル範囲を読み取る（部分抽出専用。ファイル全体要約には readbinary を使う）")
    public String readexcel(
            @P("Excel ブックのパス（絶対パスまたは相対パス）") String path,
            @P("シート名") String sheetName,
            @P("セル範囲。例: A1:C3") String range) {
        if (excelReaderTool == null) {
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
            toolExecutionTracker.record("readexcel", path + " " + sheetName + " " + range, result);
        }
        return result;
    }

    @Tool("バイナリファイルを base64 で返す（xlsx等のファイル全体要約は readbinary を優先）")
    public String readbinary(@P("読み込むバイナリファイルパス（絶対パスまたは相対パス）") String path) {
        if (binaryAttachmentStore == null) {
            return "(error) readbinaryツールが設定されていません";
        }
        if (path == null || path.isBlank()) {
            return "(error) readbinary の path が不正です";
        }
        try {
            BinaryAttachmentStore.AttachmentMetadata metadata = binaryAttachmentStore.createAttachment(path);
            String extractionSection = buildExtractionSection(path);
            if (!extractionSection.isEmpty()) {
                String result = "file=" + metadata.filename()
                    + " mime=" + metadata.mimeType()
                    + " size=" + metadata.sizeBytes()
                    + " extracted_text=\"" + extractionSection.replace("\"", "'") + "\"";
                if (toolExecutionTracker != null) {
                    toolExecutionTracker.record("readbinary", path, result);
                }
                return result;
            }

            var base64Opt = binaryAttachmentStore.getBase64(metadata.id());
            if (base64Opt.isEmpty()) {
                return "(error) readbinary の base64 変換に失敗しました";
            }
            String result = "file=" + metadata.filename()
                + " mime=" + metadata.mimeType()
                + " size=" + metadata.sizeBytes()
                + " base64=" + base64Opt.get();
            if (toolExecutionTracker != null) {
                toolExecutionTracker.record("readbinary", path, result);
            }
            return result;
        } catch (IllegalArgumentException e) {
            return "(error) " + e.getMessage();
        }
    }

    private String buildExtractionSection(String path) {
        if (path == null) {
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

    @Tool("テキストファイルに内容を保存する（許可拡張子: txt, md, csv, json, log, yaml, yml）。ワークスペースルートからの相対パスを指定すること。")
    public String writefile(
            @P("指定の保存先パス（ワークスペースルートからの相対パス。例: output/result.txt）") String path,
            @P("書き込むテキスト内容") String content) {
        if (fileWriterTool == null) {
            return "(error) writefileツールが設定されていません";
        }
        if (path == null || path.isBlank()) {
            return "(error) writefile の path が不正です";
        }
        if (content == null || content.isEmpty()) {
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

    @Tool("ローカルコマンド（git, grep, rg, nkf, ls, find, cat, head, tail, wc, stat, diff, sort, uniq, cut, tree, basename, dirname, realpath）を実行する。シェルメタ文字は拒否。nkf の `--overwrite` は利用可。タイムアウト: 既定20秒（最大30秒）、出力上限: 1000行/100KB。")
    public String localcmd(@P("実行するコマンド（例: git log -10, grep pattern file, nkf --version）") String command) {
        if (localCommandTool == null) {
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
