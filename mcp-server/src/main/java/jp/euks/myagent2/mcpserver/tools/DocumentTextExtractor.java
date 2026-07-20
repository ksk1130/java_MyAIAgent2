package jp.euks.myagent2.mcpserver.tools;



import java.util.Objects;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * Office/PDF ファイルから本文テキストを抽出するユーティリティ。
 */
public class DocumentTextExtractor {
    private static final int MAX_CHARS = 200_000;
    private static final Set<String> EXTRACTABLE_EXTENSIONS = Set.of(
            "docx", "xlsx", "xlsm", "xls", "pptx", "pdf");

    private final Path baseDir;

    public DocumentTextExtractor() {
        this(Path.of(System.getProperty("user.dir")));
    }

    /**
     * デフォルトコンストラクタ。カレントディレクトリを baseDir として使用します。
     */
    public DocumentTextExtractor(Path baseDir) {
        this.baseDir = (Objects.isNull(baseDir))
                ? Path.of(System.getProperty("user.dir"))
                : baseDir.toAbsolutePath().normalize();
    }

    /**
     * コンストラクタ。
     *
     * @param baseDir 相対パス解決の基点ディレクトリ（null の場合はカレントディレクトリ）
     */
    public boolean isExtractablePath(String pathText) {
        String ext = extensionOf(pathText);
        return ext != null && EXTRACTABLE_EXTENSIONS.contains(ext);
    }

    /**
     * 指定パスの拡張子が抽出対象かどうかを判定します。
     *
     * @param pathText 判定対象のパス文字列
     * @return 抽出可能な拡張子であれば true
     */
    public ExtractResult extract(String pathText) {
        try {
            Path resolvedPath = resolvePath(pathText);
            if (!Files.exists(resolvedPath)) {
                return ExtractResult.error("File not found: " + pathText);
            }
            if (!Files.isRegularFile(resolvedPath)) {
                return ExtractResult.error("Not a regular file: " + pathText);
            }
            if (!isExtractablePath(resolvedPath.getFileName().toString())) {
                return ExtractResult.error("extension not extractable");
            }

            BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            try (InputStream input = Files.newInputStream(resolvedPath)) {
                parser.parse(input, handler, metadata, context);
            }

            String text = normalize(handler.toString());
            if (text.isBlank()) {
                return ExtractResult.error("No extractable text");
            }
            return ExtractResult.success(text);
        } catch (Exception e) {
            return ExtractResult.error(Objects.isNull(e.getMessage()) ? "unknown error" : e.getMessage());
        }
    }

    /**
     * 指定ファイルから本文テキストを抽出します。
     *
     * @param pathText 抽出対象のファイルパス（絶対または相対）
     * @return 抽出結果を表す ExtractResult（成功時は success=true）
     */
    private Path resolvePath(String pathText) {
        Path path = Path.of(pathText);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDir.resolve(path).normalize();
    }

    /**
     * パス文字列を resolve して正規化された Path を返します。相対パスは baseDir を基点に解決します。
     *
     * @param pathText 解決対象のパス文字列
     * @return 解決済みの Path
     */
    private static String extensionOf(String pathText) {
        int dot = pathText.lastIndexOf('.');
        if (dot < 0 || dot == pathText.length() - 1) {
            return null;
        }
        return pathText.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * パス文字列から拡張子を取り出します。
     *
     * @param pathText 対象のパス文字列
     * @return 小文字化された拡張子、存在しない場合は null
     */
    private static String normalize(String text) {
        String normalized = text.replace("\r", "");
        // 連続空行を抑えてトークン消費を減らす
        normalized = normalized.replaceAll("\n{3,}", "\n\n");
        return normalized.trim();
    }

    /**
     * 抽出したテキストを正規化します（改行記号の調整・連続空行の抑制・前後の空白除去）。
     *
     * @param text 正規化対象のテキスト
     * @return 正規化済みのテキスト
     */
    public record ExtractResult(boolean success, String text, String error) {
        public static ExtractResult success(String text) {
            return new ExtractResult(true, text, "");
        }

        public static ExtractResult error(String error) {
            return new ExtractResult(false, "", Objects.isNull(error) ? "" : error);
        }
    }
}
