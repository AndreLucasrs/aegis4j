package dev.aegis4j.provider.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code doneReason} (wire: {@code done_reason}) is only present on the final
 * chunk of a stream, e.g. {@code "stop"}/{@code "length"}. {@code
 * promptEvalCount}/{@code evalCount} (wire: {@code prompt_eval_count}/
 * {@code eval_count}) are Ollama's token counts for the prompt and the
 * completion respectively — present on the single response from a
 * non-streaming {@code /api/chat} call, and on the final chunk of a
 * streaming one; {@code null} on every earlier streaming chunk.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OllamaChatResponseChunk(
        String model,
        OllamaMessage message,
        boolean done,
        @JsonProperty("done_reason") String doneReason,
        @JsonProperty("prompt_eval_count") Integer promptEvalCount,
        @JsonProperty("eval_count") Integer evalCount) {
}
