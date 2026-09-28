package dev.aegis4j.server.dto;

import java.util.List;

/** {@code stream} is optional; {@code null} and {@code false} both mean the non-streaming response shape. */
public record ChatCompletionRequestDto(String model, List<ChatMessageDto> messages, Boolean stream) {
}
