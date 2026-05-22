package jp.euks.myagent2.tools;

import com.google.common.io.Files;
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

    public BinaryAttachmentStore(Path baseDir) {
        Path resolvedBase = baseDir == null
                ? Path.of(System.getProperty("user.dir"))
                : baseDir.toAbsolutePath().normalize();
        this.baseDir = resolvedBase;
        this.attachmentsDir = this.baseDir.resolve(".myagent2").resolve("attachments");
    }

    /**
    * 指定ファイルを検証してメタ情報を保存し、参照用メタ情報を返す。
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

        String extension = Files.getFileExtension(resolved.getFileName().toString()).toLowerCase(Locale.ROOT);
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
            String extension = Files.getFileExtension(source.getFileName().toString()).toLowerCase(Locale.ROOT);
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

    public Path baseDir() {
        return baseDir;
    }

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

    private void ensureAttachmentDir() throws IOException {
        java.nio.file.Files.createDirectories(attachmentsDir);
    }

    private Path metaPath(String id) {
        return attachmentsDir.resolve(id + ".json");
    }

    private static boolean isSafeId(String id) {
        return id != null && id.matches("^[a-f0-9\\-]{36}$");
    }

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