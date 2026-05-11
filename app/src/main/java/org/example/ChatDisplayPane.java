package org.example;

import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.paint.Color;
import javafx.scene.text.TextFlow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * チャット履歴を表示する UI コンポーネント。
 * TextFlow を使用したシンタックスハイライト対応。
 * ツール実行結果は TitledPane（デフォルト折り畳み状態）で表示。
 */
public class ChatDisplayPane extends VBox {

    private static final String TOOL_RESULTS_BEGIN_MARKER = "<<TOOL_RESULTS_BEGIN>>";
    private static final String TOOL_RESULTS_END_MARKER = "<<TOOL_RESULTS_END>>";

    private final VBox contentBox;
    private final ScrollPane scrollPane;
    /** クリップボードコピー用にプレーンテキストを保持 */
    private String currentTranscript = "";

    // フォント設定（ここで変更可能）
    private static final String FONT_FAMILY = "Consolas";  // Courier New / Consolas / Menlo / Monaco
    private static final String FALLBACK_FONT = "メイリオ"; // 日本語フォント（MS ゴシック / メイリオ）
    private static final double FONT_SIZE = 14;
    
    // Font オブジェクトを静的キャッシュ
    private static final Font DEFAULT_FONT = Font.font(FONT_FAMILY + ", " + FALLBACK_FONT, FONT_SIZE);
    
    /**
     * フォント付きのスタイル文字列を生成（Text ノード用）。
     */
    private static String getStyleWithFont(String color) {
        return "-fx-fill: " + color + ";";
    }

    // シンタックスハイライト用の色定義
    private static final String COLOR_KEYWORD = "#0000AA";    // 紺（キーワード）
    private static final String COLOR_STRING = "#DD0000";     // 赤（文字列）
    private static final String COLOR_COMMENT = "#008000";    // 緑（コメント）
    private static final String COLOR_NUMBER = "#0066CC";     // 青（数値）
    private static final String COLOR_TEXT = "#000000";       // 黒（通常テキスト）
    
    // チャットラベル用の色定義
    private static final String COLOR_LABEL_YOU = "#0066CC";        // 青（You）
    private static final String COLOR_LABEL_ASSISTANT = "#008000";  // 緑（Assistant）
    private static final String COLOR_LABEL_TOOL = "#FF6600";       // オレンジ（tool:*）

    // Java キーワード
    private static final String[] KEYWORDS = {
            "public", "private", "protected", "static", "final", "class", "interface", "enum",
            "extends", "implements", "import", "package", "void", "int", "long", "double",
            "float", "boolean", "String", "new", "return", "if", "else", "for", "while",
            "do", "switch", "case", "default", "break", "continue", "true", "false", "null",
            "try", "catch", "finally", "throw", "throws", "assert", "synchronized"
    };
    private static final String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";

    public ChatDisplayPane() {
        this.contentBox = new VBox();
        // 全体のパディングを小さくして上下の余白を詰める
        this.contentBox.setPadding(new Insets(4));
        // ラベル行を分離描画しても会話行間が広がらないように最小化
        this.contentBox.setSpacing(0);
        this.contentBox.setStyle("-fx-control-inner-background: #FFFFFF;");

        this.scrollPane = new ScrollPane(contentBox);
        this.scrollPane.setFitToWidth(true);

        this.setStyle("-fx-border-color: #E0E0E0; -fx-border-width: 1;");
        this.getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    /**
     * ストリーミング中に届いたトークン（テキスト断片）を末尾に追記する。
     * 
     * @param token 追記するテキスト断片
     */
    public void appendStreamingToken(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        // 既存の最後の TextFlow に追記
        if (!contentBox.getChildren().isEmpty()) {
            javafx.scene.Node lastNode = contentBox.getChildren().get(contentBox.getChildren().size() - 1);
            if (lastNode instanceof TextFlow) {
                TextFlow tf = (TextFlow) lastNode;
                Text t = new Text(token);
                t.setFont(DEFAULT_FONT);
                tf.getChildren().add(t);
            }
        }
        scrollPane.setVvalue(1.0);
    }

    /**
     * トランスクリプト（会話履歴）全体を表示する。
     * ツール実行結果（`📌 toolname:` で始まる行グループ）は TitledPane（デフォルト折り畳み状態）で表示。
     *
     * @param transcript 会話履歴テキスト
     */
    public void updateTranscript(String transcript) {
        contentBox.getChildren().clear();
        this.currentTranscript = transcript != null ? transcript : "";

        // 不可視制御文字（ゼロ幅スペースやBOMなど）があるとTextノードの分割で
        // 一文字だけ別のレンダリングになるケースがあるため、表示前に除去する。
        String sanitized = this.currentTranscript.replaceAll("[\uFEFF\u200B\u200C\u200D]", "");
        // その他の制御文字も念のため除去（改行は保持）
        sanitized = sanitized.replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", "");

        if (transcript == null || transcript.isEmpty()) {
            TextFlow emptyFlow = new TextFlow();
            Text placeholder = new Text("（会話履歴はまだありません）");
            placeholder.setFont(DEFAULT_FONT);
            emptyFlow.getChildren().add(placeholder);
            contentBox.getChildren().add(emptyFlow);
            return;
        }

        // 行ごとに処理
        String[] lines = sanitized.split("\n", -1);
        TextFlow currentFlow = new TextFlow();
        currentFlow.setStyle("-fx-wrap-text: true;");
        
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            // 明示マーカー方式: 開始〜終了マーカーの区間をツール結果として扱う
            if (isToolBlockBeginLine(line)) {
                // BEGIN マーカー行までの currentFlow を追加（BEGIN 行自体は含めない）
                if (!currentFlow.getChildren().isEmpty()) {
                    contentBox.getChildren().add(currentFlow);
                }

                StringBuilder toolContent = new StringBuilder();
                String toolName = "tool";
                boolean toolNameResolved = false;
                i++; // BEGIN マーカー行をスキップ

                // BEGIN 直後から END マーカーまでをツール内容として収集
                while (i < lines.length && !isToolBlockEndLine(lines[i])) {
                    String nextLine = lines[i];
                    // END マーカーが欠損している履歴でも、通常の会話ラベル行に到達したら
                    // ツール折り畳みを終了して Assistant 本文を巻き込まない。
                    if (nextLine.matches("^(You|Assistant):.*")
                            && !isToolResultLine(nextLine)
                            && !isToolBlockBeginLine(nextLine)
                            && !isToolBlockEndLine(nextLine)) {
                        break;
                    }
                    if (!toolNameResolved && isToolResultLine(nextLine)) {
                        toolName = extractToolName(nextLine);
                        toolNameResolved = true;
                    }
                    if (!toolContent.isEmpty()) {
                        toolContent.append("\n");
                    }
                    toolContent.append(stripChatLabelPrefix(nextLine));
                    i++;
                }

                // END マーカー行をスキップ
                if (i < lines.length && isToolBlockEndLine(lines[i])) {
                    i++;
                }

                // END 後、続く初期応答（Assistant: ラベル）は currentFlow に追加する（新しいFlow開始）
                TitledPane toolPane = createToolPane(toolName, toolContent.toString());
                contentBox.getChildren().add(toolPane);

                currentFlow = new TextFlow();
                currentFlow.setStyle("-fx-wrap-text: true;");
                continue;
            }
            
            // ツール実行結果の開始判定：📌 で始まる行
            if (isToolResultLine(line)) {
                // 現在の TextFlow を contentBox に追加
                if (!currentFlow.getChildren().isEmpty()) {
                    contentBox.getChildren().add(currentFlow);
                }
                
                // ツール結果のすべての行を収集（次のツール結果または通常テキストまで）
                StringBuilder toolContent = new StringBuilder();
                String toolName = extractToolName(line);
                
                // 最初の行（📌 で始まる行）を追加
                toolContent.append(stripChatLabelPrefix(line));
                i++;
                
                // 後続行を収集（次のツール結果マーカーか通常テキストまで）
                while (i < lines.length) {
                    String nextLine = lines[i];
                    // 次のツール結果マーカーか、通常テキスト（チャットラベル）が出現したら終了
                    if (isToolResultLine(nextLine) || nextLine.matches("^(You|Assistant):.*")) {
                        break;
                    }
                    // 空行の次が自然文なら、ツール結果の終了とみなす
                    if (nextLine.trim().isEmpty() && i + 1 < lines.length
                            && !isToolResultLine(lines[i + 1])
                            && !isLikelyToolContinuationLine(lines[i + 1])) {
                        break;
                    }
                    toolContent.append("\n").append(stripChatLabelPrefix(nextLine));
                    i++;
                }
                
                // TitledPane を作成して追加
                TitledPane toolPane = createToolPane(toolName, toolContent.toString());
                contentBox.getChildren().add(toolPane);
                
                // 新しい TextFlow を作成
                currentFlow = new TextFlow();
                currentFlow.setStyle("-fx-wrap-text: true;");
            } else {
                // ラベル行（You:/Assistant:）は TextFlow を分離し、前行スタイルの影響を遮断する。
                boolean isLabelLine = line.matches("^(You|Assistant):.*");
                if (isLabelLine && !currentFlow.getChildren().isEmpty()) {
                    contentBox.getChildren().add(currentFlow);
                    currentFlow = new TextFlow();
                    currentFlow.setStyle("-fx-wrap-text: true;");
                }

                // 通常行をハイライト
                highlightLine(currentFlow, line);

                // 改行を追加
                if (i < lines.length - 1) {
                    Text newline = new Text("\n");
                    newline.setFont(DEFAULT_FONT);
                    currentFlow.getChildren().add(newline);
                }

                if (isLabelLine) {
                    contentBox.getChildren().add(currentFlow);
                    currentFlow = new TextFlow();
                    currentFlow.setStyle("-fx-wrap-text: true;");
                }

                i++;
            }
        }
        
        // 最後の TextFlow を追加
        if (!currentFlow.getChildren().isEmpty()) {
            contentBox.getChildren().add(currentFlow);
        }

        // スクロールを一番下に
        scrollPane.setVvalue(1.0);
    }

    /**
     * ツール実行結果行か判定する。
     * `Assistant:` 接頭辞の有無を吸収し、`(tool:*)` または `symbol + toolname:` を対象とする。
     */
    private boolean isToolResultLine(String line) {
        String normalized = normalizeToolLine(line);
        return normalized.matches("^\\(tool:\\w+\\).*")
            || normalized.matches("^[^\\p{L}\\p{N}\\s(]\\s*\\w+:.*");
    }

    /**
     * ツール名を抽出する。例：(tool:grep) -> grep、symbol localcmd: -> localcmd
     */
    private String extractToolName(String line) {
        String normalized = normalizeToolLine(line);
        // パターン1: (tool:name)
        Pattern p1 = Pattern.compile("^\\(tool:(\\w+)\\)");
        Matcher m1 = p1.matcher(normalized);
        if (m1.find()) {
            return m1.group(1);
        }
        // パターン2: symbol + name:
        Pattern p2 = Pattern.compile("^[^\\p{L}\\p{N}\\s(]\\s*(\\w+):");
        Matcher m2 = p2.matcher(normalized);
        if (m2.find()) {
            return m2.group(1);
        }
        return "tool";
    }

    /**
     * ツール判定用に `Assistant:` 接頭辞を除去した行を返す。
     */
    private String normalizeToolLine(String line) {
        if (line == null) {
            return "";
        }
        return line.replaceFirst("^Assistant:\\s+", "");
    }

    /**
     * 折り畳み表示用: 先頭の会話ラベル（You:/Assistant:）を取り除く。
     */
    private String stripChatLabelPrefix(String line) {
        if (line == null) {
            return "";
        }
        return line.replaceFirst("^(You|Assistant):\\s+", "");
    }

    private boolean isToolBlockBeginLine(String line) {
        return normalizeToolLine(line).equals(TOOL_RESULTS_BEGIN_MARKER);
    }

    private boolean isToolBlockEndLine(String line) {
        return normalizeToolLine(line).equals(TOOL_RESULTS_END_MARKER);
    }

    /**
     * 既存履歴（マーカー未導入データ）向け: ツール出力の継続行らしさを判定する。
     */
    private boolean isLikelyToolContinuationLine(String line) {
        String s = line == null ? "" : line.stripLeading();
        if (s.isEmpty()) {
            return true;
        }
        if (s.startsWith("(tool:") || s.startsWith("$ ")) {
            return true;
        }
        if (s.startsWith("|") || s.startsWith("*") || s.startsWith("...")) {
            return true;
        }
        if (s.startsWith("A\t") || s.startsWith("M\t") || s.startsWith("D\t")
                || s.startsWith("R\t") || s.startsWith("C\t") || s.startsWith("U\t")) {
            return true;
        }
        // localcmd エラー等（例: (error) ...）
        if (s.startsWith("(error)") || s.startsWith("(終了コード:")) {
            return true;
        }
        return false;
    }

    /**
     * ツール実行結果のコンテンツを抽出する。(tool:*) 以降のテキスト。
     */
    private String extractToolContent(String line) {
        Pattern p = Pattern.compile("^\\(tool:\\w+\\)\\s*");
        return p.matcher(line).replaceFirst("");
    }

    /**
     * ツール実行結果用の TitledPane を作成。デフォルト折り畳み状態。
     */
    private TitledPane createToolPane(String toolName, String content) {
        TextFlow toolContent = new TextFlow();
        toolContent.setStyle("-fx-wrap-text: true; -fx-padding: 8;");
        
        // ツール結果をハイライト
        String[] resultLines = content.split("\n", -1);
        for (int i = 0; i < resultLines.length; i++) {
            highlightLine(toolContent, resultLines[i]);
            if (i < resultLines.length - 1) {
                Text newline = new Text("\n");
                newline.setFont(DEFAULT_FONT);
                toolContent.getChildren().add(newline);
            }
        }
        
        TitledPane pane = new TitledPane(toolName + " Results", toolContent);
        pane.setCollapsible(true);
        pane.setExpanded(false);  // デフォルト折り畳み
        pane.setStyle("-fx-font-size: 12; -fx-padding: 4;");
        return pane;
    }

    /**
     * 1 行に対してシンタックスハイライトを適用する。
     */
    private void highlightLine(TextFlow target, String line) {
        // チャットラベルパターン（You: / Assistant: など）を検出
        Pattern labelPattern = Pattern.compile("^(You|Assistant):\\s+");
        Matcher labelMatcher = labelPattern.matcher(line);
        
        if (labelMatcher.find()) {
            // ラベル部分を着色
            String label = labelMatcher.group(1);
            String labelWithColon = labelMatcher.group(0);
            String color = determineColor(label);
            
            Text labelText = new Text(labelWithColon);
            labelText.setFont(DEFAULT_FONT);
            labelText.setFill(Color.web(color));
            target.getChildren().add(labelText);
            
            // 残りのテキストをハイライト（ツール出力も含む）
            String remaining = line.substring(labelMatcher.end());
            highlightWithToolMarkers(target, remaining);
        } else {
            // ラベルなし行。コメント（//）で分割し、コードっぽい行は従来どおりシンタックスハイライト、
            // 文章っぽい行は Markdown としてパースしてレンダリングする。
            int commentIndex = line.indexOf("//");
            String beforeComment = commentIndex >= 0 ? line.substring(0, commentIndex) : line;
            String commentPart = commentIndex >= 0 ? line.substring(commentIndex) : "";

            if (looksLikeCode(beforeComment)) {
                // コードっぽい行は従来ロジックで処理
                highlightCode(target, beforeComment);
                if (!commentPart.isEmpty()) {
                            Text commentText = new Text(commentPart);
                            commentText.setFont(DEFAULT_FONT);
                            commentText.setFill(Color.web(COLOR_COMMENT));
                    target.getChildren().add(commentText);
                }
            } else {
                // 文章っぽい行は Markdown パーサで処理
                for (javafx.scene.text.Text t : MarkdownFormatter.parseMarkdownLine(line)) {
                    t.setFont(DEFAULT_FONT);
                    target.getChildren().add(t);
                }
            }
        }
    }

    /**
     * 行がコードっぽいかどうかを簡易判定する。シンボルやキーワード、セミコロンなどが含まれる場合
     * はコードとみなす。リスト（- / * / 数字.）や引用（>）は文章扱いにする。
     */
    private boolean looksLikeCode(String s) {
        if (s == null) return false;
        String trimmed = s.strip();
        if (trimmed.isEmpty()) return false;

        // 明らかにリストや引用のパターンは文章扱い
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("> ")
                || trimmed.matches("^\\d+\\.\\s+.*")) {
            return false;
        }

        // ファイル差分やツール出力の可能性が高い先頭記号はコード扱い
        if (trimmed.startsWith("$") || trimmed.startsWith("|") || trimmed.startsWith("\t")
                || trimmed.startsWith("A\t") || trimmed.startsWith("M\t") || trimmed.startsWith("D\t")) {
            return true;
        }

        // コードでよく見られるトークン
        String[] codeTokens = new String[]{";","{","}","(",")","=","->","=>","import ","class ","public ","private ","protected ","static ","final ","package ","//"};
        for (String tok : codeTokens) {
            if (trimmed.contains(tok)) return true;
        }

        // 小文字主体で記号が少ない場合は文章とみなす
        return false;
    }
    
    /**
     * (tool:*) マーカーと通常テキストを分離してハイライト。
     */
    private void highlightWithToolMarkers(TextFlow target, String text) {
        Pattern toolPattern = Pattern.compile("\\(tool:\\w+\\)");
        Matcher toolMatcher = toolPattern.matcher(text);
        
        int lastEnd = 0;
        while (toolMatcher.find()) {
            // ツール前のテキストをハイライト
            if (lastEnd < toolMatcher.start()) {
                String before = text.substring(lastEnd, toolMatcher.start());
                highlightCode(target, before);
            }
            
            // (tool:*) を着色
            String toolMarker = toolMatcher.group();
            Text toolText = new Text(toolMarker);
            toolText.setFont(DEFAULT_FONT);
            toolText.setFill(Color.web(COLOR_LABEL_TOOL));
            target.getChildren().add(toolText);
            
            lastEnd = toolMatcher.end();
        }
        
        // 残りのテキストを Markdown パーサで処理して追加
        if (lastEnd < text.length()) {
            String tail = text.substring(lastEnd);
            for (javafx.scene.text.Text t : MarkdownFormatter.parseMarkdownLine(tail)) {
                t.setFont(DEFAULT_FONT);
                target.getChildren().add(t);
            }
        }
    }
    
    /**
     * ラベルに基づいて表示色を決定する。
     */
    private static String determineColor(String label) {
        if ("You".equals(label)) {
            return COLOR_LABEL_YOU;
        } else if ("Assistant".equals(label)) {
            return COLOR_LABEL_ASSISTANT;
        }
        return COLOR_TEXT;
    }

    /**
     * コード部分に対してシンタックスハイライトを適用する。
     */
    private void highlightCode(TextFlow target, String code) {
        // パターン定義
        Pattern stringPattern = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");
        Pattern keywordPattern = Pattern.compile(KEYWORD_PATTERN);
        Pattern numberPattern = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");

        int lastEnd = 0;

        while (lastEnd < code.length()) {
            int nextStringStart = Integer.MAX_VALUE;
            int nextKeywordStart = Integer.MAX_VALUE;
            int nextNumberStart = Integer.MAX_VALUE;

            // 次のトークン位置を探索
            Matcher stringMatcher = stringPattern.matcher(code.substring(lastEnd));
            if (stringMatcher.find()) {
                nextStringStart = lastEnd + stringMatcher.start();
            }

            Matcher keywordMatcher = keywordPattern.matcher(code.substring(lastEnd));
            if (keywordMatcher.find()) {
                nextKeywordStart = lastEnd + keywordMatcher.start();
            }

            Matcher numberMatcher = numberPattern.matcher(code.substring(lastEnd));
            if (numberMatcher.find()) {
                nextNumberStart = lastEnd + numberMatcher.start();
            }

            // 最も近いトークンを処理
            int nextStart = Math.min(nextStringStart, Math.min(nextKeywordStart, nextNumberStart));

            if (nextStart == Integer.MAX_VALUE) {
                // トークンなし、残りを通常テキストで追加
                if (lastEnd < code.length()) {
                    Text textNode = new Text(code.substring(lastEnd));
                    textNode.setFont(DEFAULT_FONT);
                    textNode.setFill(Color.web(COLOR_TEXT));
                    target.getChildren().add(textNode);
                }
                break;
            }

            // 前のテキストを追加
            if (lastEnd < nextStart) {
                Text textNode = new Text(code.substring(lastEnd, nextStart));
                textNode.setFont(DEFAULT_FONT);
                textNode.setFill(Color.web(COLOR_TEXT));
                target.getChildren().add(textNode);
            }

            // トークンを処理
            if (nextStart == nextStringStart) {
                Matcher m = stringPattern.matcher(code.substring(nextStart));
                if (m.find()) {
                    String token = m.group();
                    Text tokenNode = new Text(token);
                    tokenNode.setFont(DEFAULT_FONT);
                    tokenNode.setFill(Color.web(COLOR_STRING));
                    target.getChildren().add(tokenNode);
                    lastEnd = nextStart + token.length();
                } else {
                    lastEnd = nextStart + 1;
                }
            } else if (nextStart == nextKeywordStart) {
                Matcher m = keywordPattern.matcher(code.substring(nextStart));
                if (m.find()) {
                    String token = m.group();
                    Text tokenNode = new Text(token);
                    tokenNode.setFont(DEFAULT_FONT);
                    tokenNode.setFill(Color.web(COLOR_KEYWORD));
                    target.getChildren().add(tokenNode);
                    lastEnd = nextStart + token.length();
                } else {
                    lastEnd = nextStart + 1;
                }
            } else if (nextStart == nextNumberStart) {
                Matcher m = numberPattern.matcher(code.substring(nextStart));
                if (m.find()) {
                    String token = m.group();
                    Text tokenNode = new Text(token);
                    tokenNode.setFont(DEFAULT_FONT);
                    tokenNode.setFill(Color.web(COLOR_NUMBER));
                    target.getChildren().add(tokenNode);
                    lastEnd = nextStart + token.length();
                } else {
                    lastEnd = nextStart + 1;
                }
            }
        }
    }

    /**
     * スクロール位置をリセット（最上部へ）。
     */
    public void scrollToTop() {
        scrollPane.setVvalue(0.0);
    }

    /**
     * スクロール位置を最下部へ。
     */
    public void scrollToBottom() {
        scrollPane.setVvalue(1.0);
    }

    /**
     * 現在の会話履歴テキストをシステムクリップボードにコピーする。
     */
    public void copyToClipboard() {
        ClipboardContent content = new ClipboardContent();
        content.putString(currentTranscript);
        Clipboard.getSystemClipboard().setContent(content);
    }
}

