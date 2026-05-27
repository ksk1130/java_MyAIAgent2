package jp.euks.myagent2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import jp.euks.myagent2.tools.LocalCommandTool;

/**
 * LocalCommandTool のテスト。
 * 許可コマンド、危険文字拒否、タイムアウト等を検証する。
 */
public class LocalCommandToolTest {

    @Test
    public void testExecuteRejectsUnknownCommand() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("java --version");
        
        assertTrue(result, result.contains("許可されていないコマンドです"));
    }

    @Test
    public void testExecuteRejectsDangerousCharacters() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("git log; grep hello");
        
        assertTrue(result, result.contains("危険な記号が含まれています"));
    }

    @Test
    public void testExecuteRejectsShellMetaChars() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        
        // テスト1: パイプ（安全な内部パイプとして許可）
        String piped = tool.execute("git log --name-only | head -n 1");
        assertFalse(piped, piped.contains("危険な記号が含まれています"));
        
        // テスト2: リダイレクト
        assertFalse(tool.execute("git log > output.txt").contains("(tool:cmd)"));
        
        // テスト3: セミコロン
        assertFalse(tool.execute("git log; rm -rf /").contains("(tool:cmd)"));
    }

    @Test
    public void testExecuteRejectsUnpermittedGitSubcommand() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("git commit -m test");
        
        assertTrue(result, result.contains("許可されていないサブコマンド"));
    }

    @Test
    public void testExecuteAllowsPermittedGitSubcommands() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        
        // git status は許可
        String result = tool.execute("git status");
        assertTrue(result, result.contains("(tool:cmd)") || result.contains("fatal:"));
    }

    @Test
    public void testExecuteRejectsEmptyCommand() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("");
        
        assertTrue(result, result.contains("(error)"));
    }

    @Test
    public void testExecuteRejectsNullCommand() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute(null);
        
        assertTrue(result, result.contains("(error)"));
    }

    @Test
    public void testExecuteAllowsGrepCommand() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        // grep コマンド自体が存在する環境での動作
        // (実装は許可しているので、コマンドが存在するかどうかで結果が変わる可能性がある)
        String result = tool.execute("grep --version");
        
        // コマンドが存在すれば成功、なければ失敗しても OK
        assertTrue(result, result.contains("(tool:cmd)") || result.contains("(error)"));
    }

    @Test
    public void testExecuteAllowsRgCommand() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        // rg (ripgrep) コマンド（存在しない可能性あり）
        String result = tool.execute("rg --version");
        
        // コマンド不在は仕方ないが、許可コマンドリストに含まれていることを確認
        assertFalse(result, result.contains("許可されていないコマンドです"));
    }

    @Test
    public void testExecuteAllowsNkfCommand() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("nkf --version");

        // nkf が存在しなくても「許可されていない」にはならないことを確認
        assertFalse(result, result.contains("許可されていないコマンドです"));
    }

    @Test
    public void testResolvedGrepExeIsNotNull() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        // resolvedGrepExe は必ず非 null の文字列を返す
        String resolved = tool.getResolvedGrepExe();
        assertTrue("resolvedGrepExe は null であってはいけない", resolved != null && !resolved.isEmpty());
    }

    @Test
    public void testGrepRunsViaSomeExecutable() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String resolved = tool.getResolvedGrepExe();
        // 絶対パスの場合は実際にファイルが存在するか確認
        if (!resolved.equals("grep")) {
            assertTrue("Git Bash grep.exe が存在すること: " + resolved,
                Files.isExecutable(Path.of(resolved)));
        }
        // grep --version を実行して (tool:cmd) か (error) のどちらかを返すことを確認
        String result = tool.execute("grep --version");
        assertTrue(result, result.contains("(tool:cmd)") || result.contains("(error)"));
    }

    @Test
    public void testLsIsAllowed() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("ls");
        // ls は許可コマンドなので「許可されていない」エラーを返さない
        assertFalse(result, result.contains("許可されていないコマンドです"));
    }

    @Test
    public void testGitReflogIsAllowed() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("git reflog");
        // reflog は許可サブコマンドなので「許可されていない」エラーを返さない
        assertFalse(result, result.contains("許可されていないサブコマンドです"));
    }

    @Test
    public void testCatIsAllowed() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("cat build.gradle");
        // cat は許可コマンドなので「許可されていない」エラーを返さない
        assertFalse(result, result.contains("許可されていないコマンドです"));
    }

    @Test
    public void testFindExecIsBlocked() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("find . -name *.java -exec echo {} \\;");
        // 危険文字（\\)が含まれるので危険文字エラーになる
        assertTrue(result, result.contains("(error)"));
    }

    @Test
    public void testFindForbiddenOptionIsBlocked() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("find . -delete");
        assertTrue(result, result.contains("禁止オプション"));
    }

    @Test
    public void testFindWithSingleQuoteAndAsteriskIsAllowed() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("find . -name '*Test*'");
        assertFalse(result, result.contains("危険な記号が含まれています"));
    }

    @Test
    public void testCatPathTraversalIsBlocked() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("cat ../secret.txt");
        assertTrue(result, result.contains("パストラバーサル"));
    }

    @Test
    public void testCatAbsolutePathIsBlocked() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("cat /etc/passwd");
        assertTrue(result, result.contains("絶対パスは使用不可"));
    }

    @Test
    public void testCatSensitiveFileIsBlocked() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        // .env ファイル
        String result1 = tool.execute("cat .env");
        assertTrue(result1, result1.contains("機密ファイル"));
        // .pem ファイル
        String result2 = tool.execute("cat server.pem");
        assertTrue(result2, result2.contains("機密ファイル"));
        // password を含むファイル名
        String result3 = tool.execute("cat db_password.txt");
        assertTrue(result3, result3.contains("機密ファイル"));
    }

    @Test
    public void testGetResolvedExeReturnsNonEmpty() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        // ls, cat, wc の解決結果が非 null ・非空文字列であることを確認
        for (String cmd : new String[]{"ls", "cat", "wc", "head", "tail", "find"}) {
            String resolved = tool.getResolvedExe(cmd);
            assertFalse("getResolvedExe(" + cmd + ") が空ではないこと",
                resolved == null || resolved.isEmpty());
        }
    }

    @Test
    public void testConstructorCreatesAddonsDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("localcmd-addons-test-");
        Path addonsDir = tempDir.resolve("addons");
        try {
            new LocalCommandTool(tempDir, addonsDir);
            assertTrue(Files.isDirectory(addonsDir));
        } finally {
            cleanupDirectory(tempDir);
        }
    }

    @Test
    public void testResolvedExePrefersAddonsDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("localcmd-addons-resolve-");
        try {
            Path addons = tempDir.resolve("addons");
            Files.createDirectories(addons);
            Path rgExe = addons.resolve("rg.exe");
            Files.writeString(rgExe, "dummy");
            Path nkfExe = addons.resolve("nkf.exe");
            Files.writeString(nkfExe, "dummy");

            LocalCommandTool tool = new LocalCommandTool(tempDir, addons);
            assertEquals(rgExe.toString(), tool.getResolvedExe("rg"));
            assertEquals(nkfExe.toString(), tool.getResolvedExe("nkf"));
        } finally {
            cleanupDirectory(tempDir);
        }
    }

    @Test
    public void testNkfOverwriteOptionIsAllowed() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("nkf --overwrite sample.txt");

        // 実ファイルの有無で結果は変わりうるが、禁止オプション扱いではないことを確認する
        assertFalse(result, result.contains("nkf の禁止オプション"));
    }

    @Test
    public void testResolvedExeUsesFixedAddonsAcrossDifferentBaseDirs() throws Exception {
        Path baseDir1 = Files.createTempDirectory("localcmd-basedir1-");
        Path baseDir2 = Files.createTempDirectory("localcmd-basedir2-");
        Path fixedAddonsDir = Files.createTempDirectory("localcmd-fixed-addons-");
        try {
            Path rgExe = fixedAddonsDir.resolve("rg.exe");
            Files.writeString(rgExe, "dummy");

            LocalCommandTool tool1 = new LocalCommandTool(baseDir1, fixedAddonsDir);
            LocalCommandTool tool2 = new LocalCommandTool(baseDir2, fixedAddonsDir);

            assertEquals(rgExe.toString(), tool1.getResolvedExe("rg"));
            assertEquals(rgExe.toString(), tool2.getResolvedExe("rg"));
        } finally {
            cleanupDirectory(baseDir1);
            cleanupDirectory(baseDir2);
            cleanupDirectory(fixedAddonsDir);
        }
    }

    @Test
    public void testDoubleQuoteInArgumentIsAllowed() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("find . -name \"*Test*\"");
        // ダブルクオートは許可されるようになったので、危険文字エラーにはならない
        assertFalse(result, result.contains("危険な記号が含まれています"));
    }

    @Test
    public void testDoubleQuotePreservesSpacesInArgument() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("echo \"foo bar\"");
        // "foo bar" が 1 引数として処理される（実行結果は環境依存だが、エラーにはならない）
        assertFalse(result.contains("危険な記号が含まれています"));
    }

    @Test
    public void testUnclosedDoubleQuoteIsRejected() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("cat \"unclosed.txt");
        assertTrue(result.contains("(error)"));
    }

    @Test
    public void testDoubleQuotedSensitiveFileIsStillBlocked() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("cat \".env\"");
        // ダブルクオート付きでも機密ファイル判定は有効
        assertTrue(result.contains("機密ファイル"));
    }

    @Test
    public void testDoubleQuotedPathTraversalIsStillBlocked() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("cat \"../secret.txt\"");
        // ダブルクオート付きでもパストラバーサル判定は有効
        assertTrue(result.contains("パストラバーサル"));
    }

    @Test
    public void testDoubleQuoteWithEmptyStringArgument() {
        LocalCommandTool tool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        String result = tool.execute("echo \"\"");
        // 空文字列引数も許可される
        assertFalse(result.contains("危険な記号が含まれています"));
    }

    private static void cleanupDirectory(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
        }
    }
}
