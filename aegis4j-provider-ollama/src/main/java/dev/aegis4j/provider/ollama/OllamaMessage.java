package dev.aegis4j.provider.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * "Thinking" models (e.g. {@code deepseek-r1}) add a {@code thinking} field
 * to the response message carrying their chain-of-thought — ignored here,
 * not surfaced as part of the completion content.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OllamaMessage(String role, String content) {
}
