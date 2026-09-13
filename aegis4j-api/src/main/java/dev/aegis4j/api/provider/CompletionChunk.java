package dev.aegis4j.api.provider;

public record CompletionChunk(String deltaContent, boolean done, ToolCallDelta toolCallDelta) {

    public static CompletionChunk ofDelta(String deltaContent) {
        return new CompletionChunk(deltaContent, false, null);
    }

    public static CompletionChunk finished() {
        return new CompletionChunk("", true, null);
    }
}
