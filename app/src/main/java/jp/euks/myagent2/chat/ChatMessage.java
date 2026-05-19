package jp.euks.myagent2.chat;

/**
 * 会話の単一メッセージを表すレコード。
 *
 * @param role    メッセージの発信者ロール（"user" / "assistant" / "tool" 等）
 * @param content メッセージ本文
 */
public record ChatMessage(String role, String content) {}
