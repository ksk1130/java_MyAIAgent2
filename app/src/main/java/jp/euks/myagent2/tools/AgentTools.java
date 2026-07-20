package jp.euks.myagent2.tools;



import java.util.Objects;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * バイナリ読み取り専用ツール（方針B: readbinaryのみ内部実装）。
 * 
 * 他のツール（time, grep, git*, readfile, readexcel, writefile, localcmd）は MCP サーバーで実装されます。
 * readbinary のみ LangChain4j の @Tool として内部登録されます。
 */
public class AgentTools {
    private BinaryAttachmentStore binaryAttachmentStore;
    private final ToolExecutionTracker toolExecutionTracker;

    /**
     * バイナリ読み取りツール用のコンストラクタ。
     *
     * @param binaryAttachmentStore バイナリ添付ストア（null 可）
     * @param toolExecutionTracker  ツール実行トラッキング（null 可）
     */
    public AgentTools(BinaryAttachmentStore binaryAttachmentStore, ToolExecutionTracker toolExecutionTracker) {
        this.binaryAttachmentStore = binaryAttachmentStore;
        this.toolExecutionTracker = toolExecutionTracker;
    }

    /**
     * BinaryAttachmentStore を更新するセッター。
     * ワーキングディレクトリ変更時に呼ばれます。
     *
     * @param binaryAttachmentStore 新しいストア
     */
    public void setBinaryAttachmentStore(BinaryAttachmentStore binaryAttachmentStore) {
        this.binaryAttachmentStore = binaryAttachmentStore;
    }

    /**
     * 画像や文書をBase64エンコード化して返します。
     * readbinary はセキュリティ上の理由から、ファイルパスのバリデーションと許可された拡張子のチェックを行っています。
     *
     * @param path 読み込むバイナリファイルのパス（絶対パスまたは相対パス）
     * @return ファイルのメタ情報と base64 エンコードされた内容、またはエラー文字列
     */
    @Tool("画像や文書をBase64エンコード化して返す")
    public String readbinary(@P("読み込むバイナリファイルパス（絶対パスまたは相対パス）") String path) {
        if (Objects.isNull(binaryAttachmentStore)) {
            return "(error) readbinaryツールが設定されていません";
        }
        if (path == null || path.isBlank()) {
            return "(error) readbinary の path が不正です";
        }
        try {
            BinaryAttachmentStore.AttachmentMetadata metadata = binaryAttachmentStore.createAttachment(path);
            var base64Opt = binaryAttachmentStore.getBase64(metadata.id());
            if (base64Opt.isEmpty()) {
                return "(error) readbinary の base64 変換に失敗しました";
            }
            String result = "file=%s mime=%s size=%d base64=%s".formatted(
                    metadata.filename(),
                    metadata.mimeType(),
                    metadata.sizeBytes(),
                    base64Opt.get());

            if (toolExecutionTracker != null) {
                toolExecutionTracker.record("readbinary", path, result);
            }
            return result;
        } catch (IllegalArgumentException e) {
            return "(error) " + e.getMessage();
        }
    }



}



