package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * ログ出力ユーティリティ（SLF4J + Logback 経由）。
 * ログのエンコーディングは `logback.xml` 側で制御する（ここでは MS932 を推奨）。
 */
public class ExecutionLogger {
    private static final Logger log = LoggerFactory.getLogger(ExecutionLogger.class);
    private static final int MAX_REQUEST_LENGTH = 60;

    public static void logRequest(String userMessage) {
        String truncated = truncateRequest(userMessage);
        log.info("[REQUEST] 入力: \"{}\"", truncated);
    }

    public static void logToolExecution(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) return;
        String toolList = String.join(", ", toolNames);
        log.info("[TOOL] 実行: {}", toolList);
    }

    public static void logReply(String assistantMessage) {
        String truncated = truncateRequest(assistantMessage);
        log.info("[REPLY] 応答: \"{}\"", truncated);
    }

    private static String truncateRequest(String text) {
        if (text == null || text.isEmpty()) return "(empty)";
        String singleLine = text.replaceAll("\r?\n", " ");
        if (singleLine.length() <= MAX_REQUEST_LENGTH) return singleLine;
        return singleLine.substring(0, MAX_REQUEST_LENGTH) + "...";
    }
}
