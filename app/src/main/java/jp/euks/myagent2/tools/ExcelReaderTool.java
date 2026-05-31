package jp.euks.myagent2.tools;


import java.util.Objects;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;

/**
 * Excel ブックから指定したシート・セル範囲を読み取るツール。
 */
public class ExcelReaderTool {
    private static final Pattern RANGE_PATTERN = Pattern.compile("^([A-Za-z]+\\d+):([A-Za-z]+\\d+)$");
    private static final Set<String> ALLOWED_EXTS = Set.of("xlsx", "xlsm", "xls");
    private static final int MAX_CELLS = 400;
    private static final int MAX_SUMMARY_CELLS = 400;

    private final Path baseDir;

    public ExcelReaderTool() {
        this.baseDir = null;
    }

    /**
     * @param baseDir 相対パス解決の基点ディレクトリ
     */
    public ExcelReaderTool(Path baseDir) {
        this.baseDir = Objects.isNull(baseDir) ? null : baseDir.toAbsolutePath().normalize();
    }

    /**
     * 指定したシートとセル範囲の表示値を返す。
     *
     * @param path      Excel ブックのパス
     * @param sheetName シート名
     * @param range     セル範囲（例: A1:C3）
     * @return 読み取り結果またはエラーメッセージ
     */
    public String readRange(String path, String sheetName, String range) {
        try {
            Path workbookPath = resolvePath(path);
            if (!Files.exists(workbookPath)) {
                return "ERROR: File not found: " + path;
            }

            String ext = getExtension(workbookPath);
            if (Objects.isNull(ext) || !ALLOWED_EXTS.contains(ext.toLowerCase(Locale.ROOT))) {
                return "ERROR: extension not allowed: " + (Objects.isNull(ext) ? "(none)" : ext);
            }

            Matcher matcher = RANGE_PATTERN.matcher(range.trim());
            if (!matcher.matches()) {
                return "ERROR: Invalid range format. Use A1:C3 format.";
            }

            CellReference start = new CellReference(matcher.group(1).toUpperCase(Locale.ROOT));
            CellReference end = new CellReference(matcher.group(2).toUpperCase(Locale.ROOT));
            int firstRow = Math.min(start.getRow(), end.getRow());
            int lastRow = Math.max(start.getRow(), end.getRow());
            int firstCol = Math.min(start.getCol(), end.getCol());
            int lastCol = Math.max(start.getCol(), end.getCol());
            int cellCount = (lastRow - firstRow + 1) * (lastCol - firstCol + 1);
            if (cellCount > MAX_CELLS) {
                return "ERROR: Range too large. Maximum %s cells.".formatted(MAX_CELLS);
            }

            try (InputStream inputStream = Files.newInputStream(workbookPath);
                    Workbook workbook = WorkbookFactory.create(inputStream)) {
                Sheet sheet = workbook.getSheet(sheetName);
                if (Objects.isNull(sheet)) {
                    return "ERROR: Sheet not found: " + sheetName;
                }

                DataFormatter formatter = new DataFormatter(Locale.ROOT);
                FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                StringBuilder result = new StringBuilder();
                result.append("file: ").append(workbookPath.getFileName()).append('\n');
                result.append("sheet: ").append(sheetName).append('\n');
                result.append("range: ").append(range.toUpperCase(Locale.ROOT));

                for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
                    result.append('\n');
                    result.append("row ").append(rowIndex + 1).append(": ");
                    for (int colIndex = firstCol; colIndex <= lastCol; colIndex++) {
                        if (colIndex > firstCol) {
                            result.append(" | ");
                        }
                        String cellValue = readCellValue(sheet, rowIndex, colIndex, formatter, evaluator);
                        result.append(cellValue.replace("\r", " ").replace("\n", "\\n"));
                    }
                }
                return result.toString();
            }
        } catch (Exception e) {
            return "ERROR: Failed to read Excel file: " + e.getMessage();
        }
    }

    /**
     * ブック全体を要約向けのテキストとして抽出する。
     * 非空セルを先頭から最大 MAX_SUMMARY_CELLS 個まで収集する。
     *
     * @param path Excel ブックのパス
     * @return 抽出結果またはエラーメッセージ
     */
    public String readWorkbookSummary(String path) {
        try {
            Path workbookPath = resolvePath(path);
            if (!Files.exists(workbookPath)) {
                return "ERROR: File not found: " + path;
            }

            String ext = getExtension(workbookPath);
            if (Objects.isNull(ext) || !ALLOWED_EXTS.contains(ext.toLowerCase(Locale.ROOT))) {
                return "ERROR: extension not allowed: " + (Objects.isNull(ext) ? "(none)" : ext);
            }

            try (InputStream inputStream = Files.newInputStream(workbookPath);
                    Workbook workbook = WorkbookFactory.create(inputStream)) {
                DataFormatter formatter = new DataFormatter(Locale.ROOT);
                FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                StringBuilder result = new StringBuilder();
                result.append("file: ").append(workbookPath.getFileName()).append('\n');

                int collected = 0;
                for (Sheet sheet : workbook) {
                    result.append("sheet: ").append(sheet.getSheetName()).append('\n');
                    for (Row row : sheet) {
                        StringBuilder rowText = new StringBuilder();
                        boolean hasValue = false;
                        for (Cell cell : row) {
                            String value = formatter.formatCellValue(cell, evaluator).trim();
                            if (value.isEmpty()) {
                                continue;
                            }
                            if (hasValue) {
                                rowText.append(" | ");
                            }
                            rowText.append(value.replace("\r", " ").replace("\n", "\\n"));
                            hasValue = true;
                            collected++;
                            if (collected >= MAX_SUMMARY_CELLS) {
                                break;
                            }
                        }
                        if (hasValue) {
                            result.append("row ").append(row.getRowNum() + 1).append(": ").append(rowText).append('\n');
                        }
                        if (collected >= MAX_SUMMARY_CELLS) {
                            break;
                        }
                    }
                    if (collected >= MAX_SUMMARY_CELLS) {
                        result.append("... truncated ...\n");
                        break;
                    }
                }
                return result.toString().trim();
            }
        } catch (Exception e) {
            return "ERROR: Failed to read Excel workbook: " + e.getMessage();
        }
    }

    /**
     * 指定パス文字列を Path に解決します。相対パスの場合は baseDir を基点に解決します。
     *
     * @param path 読み込むファイルのパス文字列（絶対または相対）
     * @return 解決された正規化済みの Path
     */
    private Path resolvePath(String path) {
        Path raw = Path.of(path);
        if (baseDir != null && !raw.isAbsolute()) {
            return baseDir.resolve(raw).normalize();
        }
        return raw.toAbsolutePath().normalize();
    }

    /**
     * 指定シートのセル値を表示形式で取得します。
     *
     * @param sheet     対象のシート
     * @param rowIndex  行インデックス（0始まり）
     * @param colIndex  列インデックス（0始まり）
     * @param formatter セル表示用フォーマッタ
     * @param evaluator 数式評価器
     * @return セルの表示文字列（セル未存在時は空文字）
     */
    private static String readCellValue(
            Sheet sheet,
            int rowIndex,
            int colIndex,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {
        Row row = sheet.getRow(rowIndex);
        if (Objects.isNull(row)) {
            return "";
        }
        Cell cell = row.getCell(colIndex);
        if (Objects.isNull(cell)) {
            return "";
        }
        return formatter.formatCellValue(cell, evaluator);
    }

    /**
     * パスから拡張子を抽出して返します。
     *
     * @param path 対象のファイルパス
     * @return 拡張子（存在しない場合は null）
     */
    private static String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == name.length() - 1) {
            return null;
        }
        return name.substring(dotIndex + 1);
    }
}


