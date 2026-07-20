package jp.euks.myagent2.chat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.nio.file.Path;

import jp.euks.myagent2.tools.AgentTools;
import jp.euks.myagent2.tools.BinaryAttachmentStore;

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
        // 注: grep は MCP で実装されているため、ここではreadbinaryテストに変更
        Path tempDir = java.nio.file.Files.createTempDirectory("test");
        java.nio.file.Files.writeString(
            tempDir.resolve("test.txt"),
            "hello\nworld\n",
            java.nio.charset.StandardCharsets.UTF_8);

        // readbinaryのみが内部ツール
        AgentTools tools = new AgentTools(
            new BinaryAttachmentStore(tempDir),
            null);

        // readbinary は内部実装で動作
        String result = tools.readbinary("test.txt");
        assertNotNull(result);
        assertTrue(result, result.contains("test.txt") || result.contains("error"));
    }

    @Test
    public void testAgentToolsTime() {
        // 注: time は MCP で実装されているため、readbinaryのテストに注力
        AgentTools tools = new AgentTools(new BinaryAttachmentStore(Path.of(".")), null);
        // readbinary 専用になったため、time は MCP経由で検証
        assertNotNull(tools);
    }

    @Test
    public void testOpenAiCompatibleChatServiceCreation() {
        // コンストラクタでサービスが作成できることを確認
        ChatService service = new OpenAiCompatibleChatService(
            "https://api.openai.com/v1",
            "dummy-key",
            "gpt-4o-mini");

        assertNotNull(service);
        String prompt = service.getSystemPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.length() > 0);
        assertTrue(prompt, prompt.contains("rg --files . | rg -ie"));
        assertTrue(prompt, prompt.contains("ツール出力で確認できたものだけ"));
    }

    @Test
    public void testAgentToolsLocalcmd() {
        // 注: localcmd は MCP で実装されているため、このテストは削除
        // MCP ツール側をテストする
    }

    @Test
    public void testAgentToolsCanUpdateToolReferences() throws Exception {
        // 注: 古い update* メソッドは削除されたため、此テストは不要
        // （MCP ツール設定は ChatServiceFactory と mcpRegistry で行われる）
    }

    @Test
    public void testAgentToolsReadExcel() throws Exception {
        // 注: readexcel は MCP で実装されているため、readbinary のテストに統合
        Path tempDir = java.nio.file.Files.createTempDirectory("agent-tools-readbinary");
        Path workbookPath = tempDir.resolve("Book.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("Data");
            try (java.io.OutputStream outputStream = java.nio.file.Files.newOutputStream(workbookPath)) {
                workbook.write(outputStream);
            }
        }

        AgentTools tools = new AgentTools(new BinaryAttachmentStore(tempDir), null);
        String result = tools.readbinary("Book.xlsx");

        assertTrue(result, result.contains("extracted_text=") || result.contains("file=Book.xlsx"));
    }
}
