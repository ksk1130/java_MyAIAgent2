package jp.euks.myagent2.common;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 簡易Markdownパーサ。サーバー側の完全パーサではなく、UI表示用に
 * `**bold**`, `*italic*`, `` `code` ``, `# heading`, `## heading` を処理する。
 */
public class MarkdownFormatter {

    private static final String FONT_FAMILY = "Consolas";
    private static final String FALLBACK_FONT = "メイリオ";
    private static final double FONT_SIZE = 14;
    private static final Font DEFAULT_FONT = Font.font(FONT_FAMILY + ", " + FALLBACK_FONT, FONT_SIZE);

    private static final String COLOR_BOLD = "#000000";    // 太字は黒（太めに見せる）
    private static final String COLOR_ITALIC = "#333333";  // 斜体は濃いグレー
    private static final String COLOR_CODE_BG = "#F5F5F5"; // コード背景（未適用が多いので style をセットするだけ）
    private static final String COLOR_HEADING = "#003366";

    // Markdown の基本的な要素を順に処理する正規表現
    private static final Pattern BOLD_PATTERN = Pattern.compile("\"(\\*\\*|__)(.+?)\\1\"");

    // 代替アプローチ: より単純に分解して処理
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD_SIMPLE = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_SIMPLE = Pattern.compile("\\*(?!\\*)(.+?)\\*");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /**
     * テキストを解析して Text オブジェクトのリストを返す。TextFlow にそのまま追加可能。
     */
    public static List<Text> parseMarkdownLine(String line) {
        List<Text> result = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            Text t = new Text("");
            t.setFont(DEFAULT_FONT);
            result.add(t);
            return result;
        }

        // 見出しチェック
        Matcher hm = HEADING.matcher(line);
        if (hm.find()) {
            String hashes = hm.group(1);
            String rest = hm.group(2);
            double size = Math.max(18 - hashes.length() * 1.5, 12);
            Text heading = new Text(rest + "\n");
            heading.setFont(Font.font(FONT_FAMILY + ", " + FALLBACK_FONT, size));
            heading.setStyle("-fx-fill: " + COLOR_HEADING + "; -fx-font-weight: bold;");
            result.add(heading);
            return result;
        }

        // インライン処理：コード、太字、斜体 の順で単純処理
        String remaining = line;
        int offset = 0;
        // process inline code first
        Matcher codeMatcher = INLINE_CODE.matcher(remaining);
        int lastEnd = 0;
        while (codeMatcher.find()) {
            String before = remaining.substring(lastEnd, codeMatcher.start());
            if (!before.isEmpty()) {
                result.addAll(processBoldItalic(before));
            }
            String code = codeMatcher.group(1);
            Text codeText = new Text(code);
            codeText.setFont(Font.font(FONT_FAMILY + ", " + FALLBACK_FONT, FONT_SIZE));
            codeText.setStyle("-fx-fill: #333333; -fx-font-family: '" + FONT_FAMILY + "'; -fx-background-color: " + COLOR_CODE_BG + ";");
            result.add(codeText);
            lastEnd = codeMatcher.end();
        }
        if (lastEnd < remaining.length()) {
            String tail = remaining.substring(lastEnd);
            result.addAll(processBoldItalic(tail));
        }
        return result;
    }

    private static List<Text> processBoldItalic(String text) {
        List<Text> out = new ArrayList<>();
        // handle bold
        Matcher bm = BOLD_SIMPLE.matcher(text);
        int last = 0;
        while (bm.find()) {
            String before = text.substring(last, bm.start());
            if (!before.isEmpty()) {
                out.addAll(processItalicOnly(before));
            }
            String bold = bm.group(1);
            Text bt = new Text(bold);
            bt.setFont(DEFAULT_FONT);
            bt.setStyle("-fx-font-weight: bold; -fx-fill: " + COLOR_BOLD + ";");
            out.add(bt);
            last = bm.end();
        }
        if (last < text.length()) {
            out.addAll(processItalicOnly(text.substring(last)));
        }
        return out;
    }

    private static List<Text> processItalicOnly(String text) {
        List<Text> out = new ArrayList<>();
        Matcher im = ITALIC_SIMPLE.matcher(text);
        int last = 0;
        while (im.find()) {
            String before = text.substring(last, im.start());
            if (!before.isEmpty()) {
                Text t = new Text(before);
                t.setFont(DEFAULT_FONT);
                t.setStyle("-fx-fill: #000000;");
                out.add(t);
            }
            String it = im.group(1);
            Text itText = new Text(it);
            itText.setFont(DEFAULT_FONT);
            itText.setStyle("-fx-font-style: italic; -fx-fill: " + COLOR_ITALIC + ";");
            out.add(itText);
            last = im.end();
        }
        if (last < text.length()) {
            Text t = new Text(text.substring(last));
            t.setFont(DEFAULT_FONT);
            t.setStyle("-fx-fill: #000000;");
            out.add(t);
        }
        return out;
    }
}
