package dev.aegis4j.server.dto;

import java.util.List;

public record ChatCompletionRequestDto(String model, List<ChatMessageDto> messages) {
}
