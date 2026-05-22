package jp.euks.myagent2.tools;

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

    public DocumentTextExtractor(Path baseDir) {
        this.baseDir = (baseDir == null)
            ? Path.of(System.getProperty("user.dir"))
            : baseDir.toAbsolutePath().normalize();
    }

    public boolean isExtractablePath(String pathText) {
        String ext = extensionOf(pathText);
        return ext != null && EXTRACTABLE_EXTENSIONS.contains(ext);
    }

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
            return ExtractResult.error(e.getMessage() == null ? "unknown error" : e.getMessage());
        }
    }

    private Path resolvePath(String pathText) {
        Path path = Path.of(pathText);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDir.resolve(path).normalize();
    }

    private static String extensionOf(String pathText) {
        int dot = pathText.lastIndexOf('.');
        if (dot < 0 || dot == pathText.length() - 1) {
            return null;
        }
        return pathText.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String text) {
        String normalized = text.replace("\r", "");
        // 連続空行を抑えてトークン消費を減らす
        normalized = normalized.replaceAll("\n{3,}", "\n\n");
        return normalized.trim();
    }

    public record ExtractResult(boolean success, String text, String error) {
        public static ExtractResult success(String text) {
            return new ExtractResult(true, text, "");
        }

        public static ExtractResult error(String error) {
            return new ExtractResult(false, "", error == null ? "" : error);
        }
    }
}
