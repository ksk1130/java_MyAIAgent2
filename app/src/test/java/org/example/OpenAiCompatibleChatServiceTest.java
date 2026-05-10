package org.example;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

/**
 * OpenAiCompatibleChatService の単体テスト。
 * LangChain4j ベースの実装をテストします。
 */
public class OpenAiCompatibleChatServiceTest {

    @Test
    public void testAgentToolsGrep() throws Exception {
        // ワークスペース grep ツールの動作確認
        Path tempDir = java.nio.file.Files.createTempDirectory("test");
        java.nio.file.Files.writeString(
            tempDir.resolve("test.txt"),
            "hello\nworld\n",
            java.nio.charset.StandardCharsets.UTF_8);

        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(tempDir, 5);
        AgentTools tools = new AgentTools(grepTool, null, null, null);

        String result = tools.grep("world");
        assertTrue(result, result.contains("test.txt"));
    }

    @Test
    public void testAgentToolsTime() {
        AgentTools tools = new AgentTools(null, null, null, null);
        String result = tools.time();
        assertNotNull(result);
        assertTrue(result.contains("-"));
        assertTrue(result.contains(":"));
    }

    @Test
    public void testOpenAiCompatibleChatServiceCreation() {
        // コンストラクタでサービスが作成できることを確認
        Path workDir = Path.of(System.getProperty("user.dir"));
        
        ChatService service = new OpenAiCompatibleChatService(
            "https://api.openai.com/v1",
            "dummy-key",
            "gpt-4o-mini",
            new WorkspaceGrepTool(workDir),
            new GitLogTool(workDir),
            new org.example.tools.FileReaderTool(),
            new org.example.tools.FileWriterTool(workDir),
            new LocalCommandTool(workDir));

        assertNotNull(service);
        String prompt = service.getSystemPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.length() > 0);
    }

    @Test
    public void testAgentToolsLocalcmd() {
        LocalCommandTool cmdTool = new LocalCommandTool(Path.of(System.getProperty("user.dir")));
        AgentTools tools = new AgentTools(null, null, null, null, cmdTool);

        // 未許可コマンドの拒否を確認
        String result = tools.localcmd("java -version");
        assertTrue(result, result.contains("許可されていないコマンド"));
    }

    @Test
    public void testAgentToolsCanUpdateToolReferences() throws Exception {
        Path dirA = java.nio.file.Files.createTempDirectory("agent-tools-a");
        Path dirB = java.nio.file.Files.createTempDirectory("agent-tools-b");

        java.nio.file.Files.writeString(
            dirA.resolve("a.txt"),
            "only-in-a\n",
            java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(
            dirB.resolve("b.txt"),
            "only-in-b\n",
            java.nio.charset.StandardCharsets.UTF_8);

        AgentTools tools = new AgentTools(new WorkspaceGrepTool(dirA, 10), null, null, null);

        String before = tools.grep("only-in-a");
        assertTrue(before.contains("a.txt"));

        tools.updateToolReferences(new WorkspaceGrepTool(dirB, 10), null, null);

        String after = tools.grep("only-in-a");
        assertFalse(after.contains("a.txt"));

        String afterB = tools.grep("only-in-b");
        assertTrue(afterB.contains("b.txt"));
    }
}
