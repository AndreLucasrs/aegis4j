package dev.aegis4j.server.dto;

public record ChatChoiceDto(int index, ChatMessageDto message, String finishReason) {
}
