package jp.euks.myagent2.chat;

import java.util.Locale;

/**
 * Chat message roles used across the application.
 */
public enum ChatRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool");

    private final String key;

    ChatRole(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static ChatRole parse(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "user" -> USER;
            case "assistant" -> ASSISTANT;
            case "system" -> SYSTEM;
            case "tool" -> TOOL;
            default -> null;
        };
    }
}
