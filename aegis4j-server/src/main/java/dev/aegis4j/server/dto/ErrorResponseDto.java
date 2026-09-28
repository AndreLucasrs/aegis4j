package dev.aegis4j.server.dto;

/** The single error shape used everywhere in this API, {@code {"error": {"code": ..., "message": ...}}}. */
public record ErrorResponseDto(ErrorDetailDto error) {
}
