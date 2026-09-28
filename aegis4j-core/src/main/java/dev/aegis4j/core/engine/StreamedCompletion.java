package dev.aegis4j.core.engine;

import dev.aegis4j.api.provider.CompletionChunk;

import java.util.stream.Stream;

/**
 * Result of {@link Aegis4jEngine#chatStream}: {@code providerId}/{@code model}
 * are the values actually resolved for this turn — via the configured
 * {@code ModelRouter} when the request left either {@code null} — never the
 * raw, possibly-absent fields off the {@link ChatRequest}. A caller reporting
 * which model served a streamed response (e.g. in an SSE
 * {@code chat.completion.chunk} event) must use these, not
 * {@code ChatRequest.model()}.
 */
public record StreamedCompletion(String id, String providerId, String model, Stream<CompletionChunk> chunks) {
}
