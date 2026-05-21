package jp.euks.myagent2.tools;

import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

/**
 * ExcelReaderTool のユニットテスト。
 */
public class ExcelReaderToolTest {

    @Test
    public void readRange_指定範囲の表示値を返す() throws Exception {
        Path tempDir = Files.createTempDirectory("excel-reader");
        Path workbookPath = tempDir.resolve("AAA.xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("A1");
            sheet.getRow(0).createCell(1).setCellValue(123);
            sheet.createRow(1).createCell(0).setCellValue("A2");
            sheet.getRow(1).createCell(1).setCellFormula("B1*2");
            try (OutputStream outputStream = Files.newOutputStream(workbookPath)) {
                workbook.write(outputStream);
            }
        }

        ExcelReaderTool tool = new ExcelReaderTool(tempDir);
        String result = tool.readRange("AAA.xlsx", "Sheet1", "A1:B2");

        assertTrue(result, result.contains("file: AAA.xlsx"));
        assertTrue(result, result.contains("sheet: Sheet1"));
        assertTrue(result, result.contains("range: A1:B2"));
        assertTrue(result, result.contains("row 1: A1 | 123"));
        assertTrue(result, result.contains("row 2: A2 | 246"));
    }

    @Test
    public void readRange_不正な範囲はエラーを返す() throws Exception {
        Path tempDir = Files.createTempDirectory("excel-reader-invalid");
        Path workbookPath = tempDir.resolve("AAA.xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Sheet1");
            try (OutputStream outputStream = Files.newOutputStream(workbookPath)) {
                workbook.write(outputStream);
            }
        }

        ExcelReaderTool tool = new ExcelReaderTool(tempDir);
        String result = tool.readRange("AAA.xlsx", "Sheet1", "A1");

        assertTrue(result, result.startsWith("ERROR:"));
        assertTrue(result, result.contains("Invalid range format"));
    }
}