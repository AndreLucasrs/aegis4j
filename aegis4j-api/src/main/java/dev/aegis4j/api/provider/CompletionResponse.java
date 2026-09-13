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
}
