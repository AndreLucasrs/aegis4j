package dev.aegis4j.provider.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code doneReason} (wire: {@code done_reason}) is only present on the final chunk of a stream, e.g. {@code "stop"}/{@code "length"}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record OllamaChatResponseChunk(String model, OllamaMessage message, boolean done, @JsonProperty("done_reason") String doneReason) {
}
