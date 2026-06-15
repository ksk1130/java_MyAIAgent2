package jp.euks.myagent2.chat;

/**
 * 1回の LLM 呼び出しで使用されたトークン数を表すレコード。
 * @param inputTokens 入力トークン数
 * @param outputTokens 出力トークン数
 */
public record TokenInfo(int inputTokens, int outputTokens) {
    /**
     * 合計トークン数を返す。
     * @return inputTokens + outputTokens
     */
    public int total() {
        return inputTokens + outputTokens;
    }

    /**
     * プレーンテキスト形式で表示用文字列を返す。
     * @return "●トークン: input=X output=Y (計Z)"
     */
    public String toPlainString() {
        return String.format(
            "●トークン: input=%,d output=%,d (計%,d)",
            inputTokens, outputTokens, total()
        );
    }

    /**
     * HTML フォーマットで表示用文字列を返す。
     * renderTranscriptHtml で特別に処理されるマーカー行。
     * @return "input: X / output: Y / 合計: Z tokens"
     */
    public String toHtmlString() {
        return String.format(
            "input: %,d / output: %,d / 合計: %,d tokens",
            inputTokens, outputTokens, total()
        );
    }
}
