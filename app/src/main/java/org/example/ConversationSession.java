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

    ConversationSession() {
        this("", "", "", "", new ArrayList<>(), "");
    }

    public ConversationSession(
            String sessionId,
            String title,
            String createdAt,
            String updatedAt,
            List<ChatMessage> messages) {
        this(sessionId, title, createdAt, updatedAt, messages, "");
    }

    public ConversationSession(
            String sessionId,
            String title,
            String createdAt,
            String updatedAt,
            List<ChatMessage> messages,
            String workingDirectory) {
        this.sessionId = sessionId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messages = new ArrayList<>(messages == null ? List.of() : messages);
        this.workingDirectory = workingDirectory == null ? "" : workingDirectory;
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
