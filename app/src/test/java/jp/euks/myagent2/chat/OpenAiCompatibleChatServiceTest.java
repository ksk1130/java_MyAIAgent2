package jp.euks.myagent2.chat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.nio.file.Path;

import jp.euks.myagent2.tools.AgentTools;
import jp.euks.myagent2.tools.BinaryAttachmentStore;
import jp.euks.myagent2.tools.ExcelReaderTool;
import jp.euks.myagent2.tools.GitLogTool;
import jp.euks.myagent2.tools.LocalCommandTool;
import jp.euks.myagent2.tools.WorkspaceGrepTool;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

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

        WorkspaceGrepTool grepTool = new WorkspaceGrepTool(tempDir);
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
            new jp.euks.myagent2.tools.FileReaderTool(),
            new jp.euks.myagent2.tools.FileWriterTool(workDir),
            new LocalCommandTool(workDir));

        assertNotNull(service);
        String prompt = service.getSystemPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.length() > 0);
        assertTrue(prompt, prompt.contains("rg --files . | rg -ie"));
        assertTrue(prompt, prompt.contains("ツール出力で確認できたものだけ"));
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

        AgentTools tools = new AgentTools(new WorkspaceGrepTool(dirA), null, null, null);

        String before = tools.grep("only-in-a");
        assertTrue(before.contains("a.txt"));

        tools.updateToolReferences(new WorkspaceGrepTool(dirB), null, null);

        String after = tools.grep("only-in-a");
        assertFalse(after.contains("a.txt"));

        String afterB = tools.grep("only-in-b");
        assertTrue(afterB.contains("b.txt"));
    }

    @Test
    public void testAgentToolsReadExcel() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("agent-tools-excel");
        Path workbookPath = tempDir.resolve("AAA.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("A1");
            sheet.getRow(0).createCell(1).setCellValue("B1");
            sheet.createRow(1).createCell(0).setCellValue("A2");
            try (java.io.OutputStream outputStream = java.nio.file.Files.newOutputStream(workbookPath)) {
                workbook.write(outputStream);
            }
        }

        AgentTools tools = new AgentTools(null, null, null, null, null, new ExcelReaderTool(tempDir), null);
        String result = tools.readexcel("AAA.xlsx", "Sheet1", "A1:B2");

        assertTrue(result, result.contains("sheet: Sheet1"));
        assertTrue(result, result.contains("row 1: A1 | B1"));
        assertTrue(result, result.contains("row 2: A2 | "));
    }

    @Test
    public void testAgentToolsReadBinaryIncludesExtractedTextForExcel() throws Exception {
        Path tempDir = java.nio.file.Files.createTempDirectory("agent-tools-readbinary-excel");
        Path workbookPath = tempDir.resolve("Book.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Summary");
            sheet.createRow(0).createCell(0).setCellValue("売上");
            sheet.getRow(0).createCell(1).setCellValue("100");
            try (java.io.OutputStream outputStream = java.nio.file.Files.newOutputStream(workbookPath)) {
                workbook.write(outputStream);
            }
        }

        AgentTools tools = new AgentTools(
            null,
            null,
            null,
            null,
            null,
            new ExcelReaderTool(tempDir),
            new BinaryAttachmentStore(tempDir),
            null);

        String result = tools.readbinary("Book.xlsx");

        assertTrue(result, result.contains("extracted_text="));
        assertTrue(result, result.contains("売上"));
        assertFalse(result, result.contains("base64="));
    }
}
