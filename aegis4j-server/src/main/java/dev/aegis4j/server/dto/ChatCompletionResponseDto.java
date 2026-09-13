package dev.aegis4j.server.dto;

import java.util.List;

public record ChatCompletionResponseDto(
        String id,
        String object,
        long created,
        String model,
        List<ChatChoiceDto> choices,
        UsageDto usage
) {
}
