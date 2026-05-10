package org.example;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.example.tools.FileReaderTool;
import org.example.tools.FileWriterTool;

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
        this.grepTool = grepTool;
        this.gitTool = gitTool;
        this.fileReaderTool = fileReaderTool;
        this.fileWriterTool = fileWriterTool;
        this.localCommandTool = localCommandTool;
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
    public void updateFileToolReferences(org.example.tools.FileReaderTool fileReaderTool, org.example.tools.FileWriterTool fileWriterTool) {
        this.fileReaderTool = fileReaderTool;
        this.fileWriterTool = fileWriterTool;
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

    @Tool("ローカルコマンド（git, grep, rg）を実行する。シェルメタ文字は拒否。タイムアウト: 5秒、出力上限: 1000行/100KB。")
    public String localcmd(@P("実行するコマンド（例: git log -10, grep pattern file）") String command) {
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
