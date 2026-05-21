package jp.euks.myagent2.tools;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Set;
import java.util.Locale;
import com.google.common.base.Strings;

/**
 * ファイル読み取り用のツールクラス。
 * セキュリティ対策として拡張子ホワイトリスト方式でテキスト系ファイルのみを許可します。
 *
 * <p>読み込みはまず UTF-8 を試し、デコードエラーが発生した場合は Shift_JIS (Windows-31J) にフォールバックします。
 * {@code readFile(String path)} メソッドは {@code DefaultManualToolExecutor} の
 * {@code /tool readfile <path>} コマンドおよび LLM Function Calling の両方から利用されます。
 * </p>
 */
public class FileReaderTool {

    /** ファイル内容の最大文字数。超過分は切り捨て警告を付与して返す。 */
    private static final int MAX_CHARS = 20000;

    /** 許可する拡張子（小文字で管理）。 */
    private static final Set<String> ALLOWED_EXTS = Set.of(
            "txt", "md", "json", "csv", "java", "py", "xml", "html", "htm",
            "yaml", "yml", "properties", "sql", "sh", "cob", "cpy", "cbl", "ps1");

    /** 代替エンコーディング（Windows 環境の SJIS 互換）。 */
    private static final Charset SHIFT_JIS = Charset.forName("Windows-31J");

    /** 相対パス解決の基点ディレクトリ。null の場合は JVM の user.dir を使用。 */
    private final Path baseDir;

    /** ベースディレクトリなしで構築する（後方互換）。 */
    public FileReaderTool() {
        this.baseDir = null;
    }

    /**
     * @param baseDir 相対パス解決の基点ディレクトリ（setdir に連動）
     */
    public FileReaderTool(Path baseDir) {
        this.baseDir = baseDir == null ? null : baseDir.toAbsolutePath().normalize();
    }

    /**
     * テキストファイルを読み込み、内容を返します。
     *
     * <p>相対パスが指定された場合は baseDir（未設定時は JVM の user.dir）を基点として解決します。
     * UTF-8 デコードに失敗した場合は Windows-31J (Shift_JIS) でリトライします。
     * 拡張子がホワイトリスト外の場合はエラー文字列を返します。</p>
     *
     * @param path 読み込むファイルパス（絶対パスまたは相対パス）
     * @return ファイル内容、またはエラーメッセージ（"ERROR: ..." 形式）
     */
    public String readFile(String path) {
        try {
            Path raw = Path.of(path);
            Path p = (baseDir != null && !raw.isAbsolute())
                    ? baseDir.resolve(raw).normalize()
                    : raw.toAbsolutePath().normalize();

            if (!Files.exists(p)) {
                return "ERROR: File not found: " + path;
            }

            String ext = getExtension(p);
            if (Strings.isNullOrEmpty(ext) || !ALLOWED_EXTS.contains(ext.toLowerCase(Locale.ROOT))) {
                return "ERROR: extension not allowed: " + (ext == null ? "(none)" : ext);
            }

            byte[] bytes = Files.readAllBytes(p);

            // まず UTF-8 で厳格デコードを試みる
            String content;
            try {
                content = decode(bytes, StandardCharsets.UTF_8);
            } catch (CharacterCodingException e) {
                // UTF-8 失敗 → Shift_JIS にフォールバック
                try {
                    content = decode(bytes, SHIFT_JIS);
                } catch (CharacterCodingException ex) {
                    return "ERROR: Failed to decode file with UTF-8 and Shift_JIS: " + ex.getMessage();
                }
            }

            if (content.length() > MAX_CHARS) {
                return "WARNING: file truncated to " + MAX_CHARS + " chars.\n" + content.substring(0, MAX_CHARS);
            }
            return content;
        } catch (Exception e) {
            return "ERROR: Failed to read file: " + e.getMessage();
        }
    }

    /**
     * 指定文字コードでバイト列をデコードします。
     *
     * @param bytes   デコード対象のバイト列
     * @param charset 使用する文字コード
     * @return デコード結果
     * @throws CharacterCodingException デコードできない場合
     */
    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        CharBuffer cb = decoder.decode(bb);
        return cb.toString();
    }

    /**
     * ファイルパスから拡張子を取得します。
     *
     * @param p 対象パス
     * @return 拡張子。存在しない場合は null
     */
    private static String getExtension(Path p) {
        return com.google.common.io.Files.getFileExtension(p.getFileName().toString());
    }
}
