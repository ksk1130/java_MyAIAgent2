package org.example;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会話履歴を1セッション単位で保持するデータモデル。
 */
public final class ConversationSession {
    private String sessionId;
    private String title;
    private String createdAt;
    private String updatedAt;
    private List<ChatMessage> messages;
    private String workingDirectory;
    /** トランスクリプト文字列（ツール結果マーカーを含む）。JSON に永続化する。 */
    private String transcript;

    ConversationSession() {
        this("", "", "", "", new ArrayList<>(), "", null);
    }

    public ConversationSession(
            String sessionId,
            String title,
            String createdAt,
            String updatedAt,
            List<ChatMessage> messages) {
        this(sessionId, title, createdAt, updatedAt, messages, "", null);
    }

    public ConversationSession(
            String sessionId,
            String title,
            String createdAt,
            String updatedAt,
            List<ChatMessage> messages,
            String workingDirectory) {
        this(sessionId, title, createdAt, updatedAt, messages, workingDirectory, null);
    }

    public ConversationSession(
            String sessionId,
            String title,
            String createdAt,
            String updatedAt,
            List<ChatMessage> messages,
            String workingDirectory,
            String transcript) {
        this.sessionId = sessionId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messages = new ArrayList<>(messages == null ? List.of() : messages);
        this.workingDirectory = workingDirectory == null ? "" : workingDirectory;
        this.transcript = transcript;
    }

    public static ConversationSession createNew(Clock clock) {
        String now = OffsetDateTime.now(clock).toString();
        return new ConversationSession(
            UUID.randomUUID().toString(),
            "New Chat",
            now,
            now,
            new ArrayList<>(),
            "");
    }

    public String sessionId() {
        return sessionId;
    }

    public String title() {
        return title;
    }

    public String createdAt() {
        return createdAt;
    }

    public String updatedAt() {
        return updatedAt;
    }

    public List<ChatMessage> messages() {
        return List.copyOf(messages == null ? List.of() : messages);
    }

    public String workingDirectory() {
        return workingDirectory == null ? "" : workingDirectory;
    }

    public void setWorkingDirectory(String path) {
        this.workingDirectory = path == null ? "" : path;
    }

    /** 保存済みのトランスクリプト文字列を返す。未保存の場合は null。 */
    public String transcript() {
        return transcript;
    }

    /** トランスクリプト文字列を設定する。 */
    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    public void replaceMessages(List<ChatMessage> newMessages) {
        this.messages = new ArrayList<>(newMessages == null ? List.of() : newMessages);
        this.title = inferTitle(this.messages, this.title);
    }

    public void touch(Clock clock) {
        this.updatedAt = OffsetDateTime.now(clock).toString();
    }

    private static String inferTitle(List<ChatMessage> messages, String fallback) {
        for (ChatMessage message : messages) {
            if (!"user".equals(message.role())) {
                continue;
            }
            String content = message.content() == null ? "" : message.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() <= 32) {
                return content;
            }
            return content.substring(0, 32) + "...";
        }
        return (fallback == null || fallback.isBlank()) ? "New Chat" : fallback;
    }
}
