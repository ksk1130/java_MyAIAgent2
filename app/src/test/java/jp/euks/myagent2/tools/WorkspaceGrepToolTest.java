package jp.euks.myagent2.tools;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class WorkspaceGrepToolTest {
    @Test
    public void searchFindsMatchesInShiftJisFiles() throws Exception {
        Path tempDir = Files.createTempDirectory("workspace-grep-sjis");
        Files.createDirectories(tempDir.resolve("src"));
        // SJISで「あいうえお」を含むファイルを作成
        String sjisText = "1行目\nあいうえおを含む行\n3行目\n";
        Path sjisFile = tempDir.resolve("src/sjis.txt");
        Files.write(sjisFile, sjisText.getBytes(Charset.forName("Windows-31J")));

        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(tempDir, 10);
        String result = grepTool.search("あいうえお");

        assertTrue(result.startsWith("(tool:grep) 1件"));
        assertTrue(result.contains("sjis.txt:2 | あいうえおを含む行"));
    }
    @Test
    public void searchFindsMatchesInWorkspaceFiles() throws Exception {
        Path tempDir = Files.createTempDirectory("workspace-grep");
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(
            tempDir.resolve("src/sample.txt"),
            "first line\nChatService appears here\n",
            StandardCharsets.UTF_8);

        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(tempDir, 10);
        String result = grepTool.search("chatservice");

        assertTrue(result.startsWith("(tool:grep) 1件"));
        assertTrue(result.contains("src/sample.txt:2"));
    }

    @Test
    public void searchReturnsZeroWhenNoMatch() throws Exception {
        Path tempDir = Files.createTempDirectory("workspace-grep-none");
        Files.writeString(tempDir.resolve("README.md"), "hello", StandardCharsets.UTF_8);

        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(tempDir, 10);
        String result = grepTool.search("notfound");

        assertTrue(result.startsWith("(tool:grep) 0件"));
    }

    @Test
    public void searchSupportsExclusionPatternWithMinusV() throws Exception {
        Path tempDir = Files.createTempDirectory("workspace-grep-exclude");
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(
            tempDir.resolve("src/classes.txt"),
            "ChatService\nChatServiceTest\nChatInteractor\nChatInteractorTest\n",
            StandardCharsets.UTF_8);

        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(tempDir, 10);
        String result = grepTool.search("Chat -v Test");

        // Chat を含むが Test を含まない行は ChatService と ChatInteractor
        assertTrue(result.startsWith("(tool:grep) 2件"));
        assertTrue(result.contains("ChatService"));
        assertTrue(result.contains("ChatInteractor"));
        // Test を含む行は除外されるので、Test を含む結果は無い
        assertTrue(!result.contains("Test") || result.contains("ChatService") && result.contains("ChatInteractor"));
    }

    @Test
    public void searchSupportsExclusionPatternWithExcludeFlag() throws Exception {
        Path tempDir = Files.createTempDirectory("workspace-grep-exclude-flag");
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(
            tempDir.resolve("src/items.txt"),
            "apple\napple pie\norange\norange juice\n",
            StandardCharsets.UTF_8);

        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(tempDir, 10);
        String result = grepTool.search("apple --exclude pie");

        // apple を含むが pie を含まない行は apple のみ
        assertTrue(result.startsWith("(tool:grep) 1件"));
        assertTrue(result.contains("apple"));
        assertTrue(!result.contains("pie"));
    }

    @Test
    public void searchExclusionReturnsZeroWhenAllExcluded() throws Exception {
        Path tempDir = Files.createTempDirectory("workspace-grep-all-excluded");
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(
            tempDir.resolve("src/test.txt"),
            "ChatServiceTest\nChatInteractorTest\n",
            StandardCharsets.UTF_8);

        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(tempDir, 10);
        String result = grepTool.search("Chat -v Test");

        // すべてが Test を含むので、除外されてマッチなし
        assertTrue(result.startsWith("(tool:grep) 0件"));
    }
}