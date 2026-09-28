package dev.aegis4j.api.provider;

import java.util.List;

public record CompletionResponse(
        String id,
        String model,
        String content,
        FinishReason finishReason,
        Usage usage,
        List<ToolCall> toolCalls
) {

    /** A third-party {@link Provider} returning {@code toolCalls: null} must not NPE every caller that checks {@code toolCalls().isEmpty()}. */
    public CompletionResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
