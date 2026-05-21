package jp.euks.myagent2.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

/**
 * ファイル書き込み用ツール。セキュリティ上の理由から以下の制約を設けている。
 * <ul>
 *   <li>書き込み先はコンストラクタで指定したベースディレクトリ配下のみ（パストラバーサル防止）</li>
 *   <li>許可する拡張子：txt, md, csv, json, log, yaml, yml のみ</li>
 *   <li>コンテンツの最大文字数：50,000 文字</li>
 * </ul>
 */
public class FileWriterTool {

    /** 書き込みコンテンツの最大文字数。 */
    private static final int MAX_CONTENT_CHARS = 50_000;

    /** 書き込みを許可するファイル拡張子（小文字）。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".txt", ".md", ".csv", ".json", ".log", ".yaml", ".yml", ".ps1");

    private final Path baseDir;

    /**
     * @param baseDir 書き込みを許可するベースディレクトリ（ワークスペースルートなど）
     */
    public FileWriterTool(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    /**
     * 指定したパスにテキストを書き込む。
     *
     * @param relativePath ベースディレクトリからの相対パス（例: "output/result.txt"）
     * @param content      書き込む文字列
     * @return 成功メッセージ、またはエラーメッセージ（"(error) ..." 形式）
     */
    public String writeFile(String relativePath, String content) {
        if (relativePath == null || relativePath.isBlank()) {
            return "(error) パスが空です";
        }
        if (content == null) {
            content = "";
        }
        content = normalizeEscapedNewlines(content);
        if (content.length() > MAX_CONTENT_CHARS) {
            return "(error) コンテンツが長すぎます（最大 " + MAX_CONTENT_CHARS + " 文字）";
        }

        // 拡張子チェック
        String lower = relativePath.toLowerCase(java.util.Locale.ROOT);
        boolean extensionAllowed = ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!extensionAllowed) {
            return "(error) 許可されていないファイル拡張子です。許可拡張子: " + ALLOWED_EXTENSIONS;
        }

        Path targetPath;
        try {
            targetPath = baseDir.resolve(relativePath).normalize();
        } catch (InvalidPathException e) {
            return "(error) パスが不正です: " + e.getMessage();
        }

        // パストラバーサル防止
        if (!targetPath.startsWith(baseDir)) {
            return "(error) ワークスペース外への書き込みは禁止されています";
        }

        try {
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(targetPath, content);
            return "ファイルを保存しました: " + baseDir.relativize(targetPath);
        } catch (IOException e) {
            return "(error) ファイルの書き込みに失敗しました: " + e.getMessage();
        }
    }

    /** ベースディレクトリを返す（テスト用）。 */
    Path baseDir() {
        return baseDir;
    }

    /**
     * モデルが "\\n" をリテラルとして返す場合にのみ改行へ変換する。
     */
    private String normalizeEscapedNewlines(String content) {
        if (content.contains("\n")) {
            return content;
        }
        return content
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n");
    }
}
