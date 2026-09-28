package dev.aegis4j.server.dto;

import java.util.List;

/** OpenAI {@code chat.completion.chunk} shape, one per {@code data:} event in an SSE stream. */
public record ChatCompletionChunkDto(
        String id,
        String object,
        long created,
        String model,
        List<ChatChunkChoiceDto> choices
) {
}
