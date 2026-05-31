package jp.euks.myagent2.tools;

import java.util.Objects;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * バイナリ添付のメタ情報を保存し、attachmentId から元ファイルを参照するストア。
 */
public class BinaryAttachmentStore {
    private static final long MAX_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "png", "jpeg", "jpg", "xlsx", "docx", "pptx");

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path baseDir;
    private final Path attachmentsDir;

    /**
     * デフォルトコンストラクタ。カレントディレクトリを baseDir として使用します。
     * 
     * @param baseDir 添付ファイルの保存先の基点となるディレクトリ（null の場合はカレントディレクトリ）
     */
    public BinaryAttachmentStore(Path baseDir) {
        Path resolvedBase = Objects.isNull(baseDir)
                ? Path.of(System.getProperty("user.dir"))
                : baseDir.toAbsolutePath().normalize();
        this.baseDir = resolvedBase;
        this.attachmentsDir = this.baseDir.resolve(".myagent2").resolve("attachments");
    }

    /**
     * 指定したパスのファイルを添付として検証・保存し、メタ情報を返します。
     * 添付は attachmentsDir に保存され、メタ情報は JSON ファイルとして管理されます。
     * 
     * @param pathText 添付対象のファイルパス（絶対またはワークスペース相対）
     * @return 保存された添付のメタ情報
     * @throws IllegalArgumentException 添付の検証や保存に失敗した場合
     */
    public AttachmentMetadata createAttachment(String pathText) {
        if (pathText == null || pathText.isBlank()) {
            throw new IllegalArgumentException("readbinary の path が不正です");
        }

        Path resolved = resolveInsideBaseDir(pathText);
        if (!java.nio.file.Files.exists(resolved)) {
            throw new IllegalArgumentException("ファイルが見つかりません: " + pathText);
        }
        if (!java.nio.file.Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("ファイルではありません: " + pathText);
        }

        String extension = getFileExtension(resolved.getFileName().toString()).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("拡張子が許可されていません: " + extension);
        }

        try {
            long size = java.nio.file.Files.size(resolved);
            if (size > MAX_BYTES) {
                throw new IllegalArgumentException("ファイルサイズが上限を超えています（10MB）");
            }

            byte[] data = java.nio.file.Files.readAllBytes(resolved);
            ensureAttachmentDir();

            String id = UUID.randomUUID().toString();
            AttachmentMetadata metadata = new AttachmentMetadata(
                    id,
                    resolved.toString(),
                    resolved.getFileName().toString(),
                    toMimeType(extension),
                    size,
                    OffsetDateTime.now().toString(),
                    sha256Hex(data));

            java.nio.file.Files.writeString(
                    metaPath(id),
                    gson.toJson(metadata),
                    StandardCharsets.UTF_8);

            return metadata;
        } catch (IOException e) {
            throw new IllegalArgumentException("添付保存に失敗しました: " + e.getMessage());
        }
    }

    /**
     * メタ情報 JSON を読み込み AttachmentMetadata を返します。
     *
     * @param id 添付の識別子（UUID 形式）
     * @return メタ情報が存在すれば Optional に格納して返す
     */
    public Optional<AttachmentMetadata> getMeta(String id) {
        if (!isSafeId(id)) {
            return Optional.empty();
        }
        Path path = metaPath(id);
        if (!java.nio.file.Files.exists(path)) {
            return Optional.empty();
        }

        try {
            String json = java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
            AttachmentMetadata metadata = gson.fromJson(json, AttachmentMetadata.class);
            return Optional.ofNullable(metadata);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 指定した添付 ID に対応する元ファイルを base64 エンコードして返します。
     * 
     * @param id 添付の識別子
     * @return 元ファイルの base64 エンコード文字列を含む Optional（失敗時は空）
     */
    public Optional<String> getBase64(String id) {
        Optional<AttachmentMetadata> metadataOpt = getMeta(id);
        if (metadataOpt.isEmpty()) {
            return Optional.empty();
        }

        AttachmentMetadata metadata = metadataOpt.get();
        if (metadata.sourcePath() == null || metadata.sourcePath().isBlank()) {
            return Optional.empty();
        }

        try {
            Path source = Path.of(metadata.sourcePath()).toAbsolutePath().normalize();
            if (!java.nio.file.Files.exists(source) || !java.nio.file.Files.isRegularFile(source)) {
                return Optional.empty();
            }
            String extension = getFileExtension(source.getFileName().toString()).toLowerCase(Locale.ROOT);
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                return Optional.empty();
            }
            long size = java.nio.file.Files.size(source);
            if (size > MAX_BYTES) {
                return Optional.empty();
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(source);
            return Optional.of(Base64.getEncoder().encodeToString(bytes));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * 指定した添付 ID に対応する元ファイルが存在するかを返します。
     *
     * @param id 添付の識別子
     * @return 元ファイルが存在すれば true
     */
    public boolean exists(String id) {
        Optional<AttachmentMetadata> metadataOpt = getMeta(id);
        if (metadataOpt.isEmpty()) {
            return false;
        }
        String sourcePath = metadataOpt.get().sourcePath();
        if (sourcePath == null || sourcePath.isBlank()) {
            return false;
        }
        return java.nio.file.Files.exists(Path.of(sourcePath));
    }

    /**
     * ファイル名から拡張子を抽出して返します。拡張子がない場合は空文字を返します。
     *
     * @param fileName ファイル名
     * @return 拡張子（小文字）。拡張子がない場合は空文字。
     */
    private static String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1);
    }

    /**
     * このストアのベースディレクトリを返します（テスト用）。
     * 
     * @return baseDir このストアが使用するベースディレクトリの Path
     */
    public Path baseDir() {
        return baseDir;
    }

    /**
     * ベースディレクトリ内に解決されたパスを返します。ベース外への参照は例外となります。
     *
     * @param pathText 解決対象のパス文字列（絶対または相対）
     * @return baseDir を基準に正規化された Path
     */
    private Path resolveInsideBaseDir(String pathText) {
        Path raw = Path.of(pathText);
        Path resolved = raw.isAbsolute()
                ? raw.toAbsolutePath().normalize()
                : baseDir.resolve(raw).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("ベースディレクトリ外のパスは指定できません: " + pathText);
        }
        return resolved;
    }

    /**
     * 添付メタ情報を保存するディレクトリを作成します（存在しない場合）。
     *
     * @throws IOException ディレクトリ作成に失敗した場合
     */
    private void ensureAttachmentDir() throws IOException {
        java.nio.file.Files.createDirectories(attachmentsDir);
    }

    /**
     * 添付メタ情報 JSON ファイルのパスを返します。
     *
     * @param id 添付の識別子
     * @return attachmentsDir/<id>.json の Path
     */
    private Path metaPath(String id) {
        return attachmentsDir.resolve(id + ".json");
    }

    /**
     * 添付 ID が安全な形式（UUID 形式の 36 文字）か検証します。
     *
     * @param id 検証対象の ID
     * @return 形式が妥当であれば true
     */
    private static boolean isSafeId(String id) {
        return id != null && id.matches("^[a-f0-9\\-]{36}$");
    }

    /**
     * バイト配列の SHA-256 ハッシュを 16 進文字列で返します。
     *
     * @param bytes ハッシュ化対象のバイト配列
     * @return SHA-256 ハッシュの 16 進表現
     */
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が利用できません", e);
        }
    }

    /**
     * ファイル拡張子から MIME タイプを推定して返します。
     *
     * @param extension 小文字の拡張子（例: "pdf", "png"）
     * @return 推定される MIME タイプ。既知の拡張子でなければ application/octet-stream を返す。
     */
    private static String toMimeType(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpeg", "jpg" -> "image/jpeg";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }

    /**
     * 添付ファイルのメタ情報を表すレコードクラス。JSON に永続化されます。
     *
     * @param id         添付の識別子（UUID 形式）
     * @param sourcePath 元ファイルのパス
     * @param filename   元ファイルの名前
     * @param mimeType   元ファイルの MIME タイプ
     * @param sizeBytes  元ファイルのサイズ（バイト単位）
     * @param createdAt  添付が作成された日時（ISO-8601 文字列）
     * @param sha256     元ファイルの SHA-256 ハッシュ（16 進文字列）
     */
    public record AttachmentMetadata(
            String id,
            String sourcePath,
            String filename,
            String mimeType,
            long sizeBytes,
            String createdAt,
            String sha256) {
    }
}
