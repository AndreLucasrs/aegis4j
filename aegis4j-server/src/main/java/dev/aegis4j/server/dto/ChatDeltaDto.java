package dev.aegis4j.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code role} is only present on the first chunk of a stream, {@code content} is absent on the final (done) chunk. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatDeltaDto(String role, String content) {
}
