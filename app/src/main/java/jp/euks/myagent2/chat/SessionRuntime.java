package jp.euks.myagent2.chat;

import java.time.Instant;

/**
 * 1セッション分のランタイム状態を保持する簡易コンテナ。
 */
public class SessionRuntime {
    private final String sessionId;
    private final ChatService chatService;
    private final ChatInteractor interactor;
    private volatile long lastAccessMillis;

    public SessionRuntime(String sessionId, ChatService chatService, ChatInteractor interactor) {
        this.sessionId = sessionId;
        this.chatService = chatService;
        this.interactor = interactor;
        touch();
    }

    public String getSessionId() {
        return sessionId;
    }

    public ChatService getChatService() {
        return chatService;
    }

    public ChatInteractor getInteractor() {
        return interactor;
    }

    public void touch() {
        this.lastAccessMillis = Instant.now().toEpochMilli();
    }

    public long getLastAccessMillis() {
        return lastAccessMillis;
    }
}
