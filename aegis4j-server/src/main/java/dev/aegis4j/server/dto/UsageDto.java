package dev.aegis4j.server.dto;

public record UsageDto(int promptTokens, int completionTokens, int totalTokens) {
}
