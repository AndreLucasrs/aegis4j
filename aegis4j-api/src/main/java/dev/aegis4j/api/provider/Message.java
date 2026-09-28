package dev.aegis4j.api.provider;

import java.util.List;

/**
 * {@code toolCalls} and {@code toolCallId} are only meaningful for tool
 * calling: an {@code ASSISTANT} message carries {@code toolCalls} when it is
 * the model's request to invoke tools, and a {@code TOOL} message carries
 * {@code toolCallId} to say which of those calls it is the result of. The
 * two-arg constructor stays the canonical way to build plain text messages so
 * existing callers of {@code new Message(role, content)} keep compiling.
 */
public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public Message {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public Message(Role role, String content) {
        this(role, content, List.of(), null);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content);
    }

    public static Message assistantToolCall(String content, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, toolCalls, null);
    }

    public static Message toolResult(String toolCallId, String content) {
        return new Message(Role.TOOL, content, List.of(), toolCallId);
    }
}
