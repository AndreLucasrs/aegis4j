package dev.aegis4j.server.dto;

public record ChatChunkChoiceDto(int index, ChatDeltaDto delta, String finishReason) {
}
