package dev.aegis4j.api.provider;

/**
 * {@code finishReason} is best-effort and provider-specific: {@code null}
 * means the provider's streaming wire format didn't carry a reason for this
 * chunk (true for every non-terminal chunk, and for terminal chunks from
 * providers that don't expose one on their streaming API, e.g. Anthropic's
 * {@code message_stop} event). A caller that needs a reason on the terminal
 * chunk and gets {@code null} should treat it the same as {@link FinishReason#STOP} —
 * the stream still ended normally, the specific cause is just unknown.
 */
public record CompletionChunk(String deltaContent, boolean done, ToolCallDelta toolCallDelta, FinishReason finishReason) {

    public static CompletionChunk ofDelta(String deltaContent) {
        return new CompletionChunk(deltaContent, false, null, null);
    }

    /** Terminal chunk with no known finish reason — see the class javadoc for how callers should treat that. */
    public static CompletionChunk finished() {
        return new CompletionChunk("", true, null, null);
    }

    public static CompletionChunk finished(FinishReason finishReason) {
        return new CompletionChunk("", true, null, finishReason);
    }
}
