package jp.euks.myagent2.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;
import jp.euks.myagent2.mcpserver.tools.WorkspaceGrepTool;

public class DefaultManualToolExecutorTest {
    @Test
    public void tryExecuteReturnsEmptyForNormalMessage() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        Optional<String> result = executor.tryExecute("hello");

        assertFalse(result.isPresent());
    }

    @Test
    public void tryExecuteRunsTimeTool() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-05-02T10:30:40Z"), ZoneId.of("UTC"));
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor(fixedClock);

        String result = executor.tryExecute("/tool time").orElse("");

        assertEquals("(tool:time) 2026-05-02 10:30:40", result);
    }

    @Test
    public void tryExecuteRunsEchoTool() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        String result = executor.tryExecute("/tool echo hi").orElse("");

        assertEquals("(tool:echo) hi", result);
    }

    @Test
    public void tryExecuteReturnsHelpText() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        String result = executor.tryExecute("/tool help").orElse("");

        assertTrue(result.startsWith("(tool:help)"));
    }

    @Test
    public void tryExecuteRunsGrepTool() throws Exception {
        Path tempDir = Files.createTempDirectory("grep-test");
        Path testFile = tempDir.resolve("notes.txt");
        Files.writeString(testFile, "alpha\nbeta match\ngamma\n", StandardCharsets.UTF_8);

        DefaultManualToolExecutor executor = new DefaultManualToolExecutor(
            Clock.systemUTC(),
            new WorkspaceGrepTool(tempDir));

        String result = executor.tryExecute("/tool grep beta").orElse("");

        assertTrue(result.startsWith("(tool:grep:"));
        assertTrue(result.contains("notes.txt:2 | beta match"));
    }

    @Test
    public void tryExecuteRunsCmdToolForGitStatus() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        String result = executor.tryExecute("/tool cmd git status").orElse("");

        assertTrue(result.contains("$ git status") && (result.contains("(tool:cmd)") || result.contains("(error)") 
            || result.contains("fatal:")));
    }

    @Test
    public void tryExecuteAllowsCmdWithPipe() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        String result = executor.tryExecute("/tool cmd git log | grep").orElse("");

        assertFalse(result, result.contains("危険な記号が含まれています"));
    }

    @Test
    public void tryExecuteRejectsCmdWithUnpermittedCommand() {
        DefaultManualToolExecutor executor = new DefaultManualToolExecutor();

        String result = executor.tryExecute("/tool cmd java -version").orElse("");

        assertTrue(result, result.contains("許可されていないコマンドです"));
    }

    @Test
    public void tryExecuteRunsReadExcelTool() throws Exception {
        Path tempDir = Files.createTempDirectory("excel-test");
        Path workbookPath = tempDir.resolve("AAA.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("v11");
            sheet.getRow(0).createCell(1).setCellValue("v12");
            sheet.createRow(1).createCell(0).setCellValue("v21");
            sheet.getRow(1).createCell(1).setCellValue("v22");
            try (java.io.OutputStream outputStream = Files.newOutputStream(workbookPath)) {
                workbook.write(outputStream);
            }
        }

        DefaultManualToolExecutor executor = new DefaultManualToolExecutor(Clock.systemUTC(), tempDir);

        String result = executor.tryExecute("/tool readexcel AAA.xlsx Sheet1 A1:B2").orElse("");

        assertTrue(result, result.startsWith("(tool:readexcel) file: AAA.xlsx"));
        assertTrue(result, result.contains("row 1: v11 | v12"));
        assertTrue(result, result.contains("row 2: v21 | v22"));
    }
}