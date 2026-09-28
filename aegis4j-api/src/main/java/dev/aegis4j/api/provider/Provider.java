package dev.aegis4j.api.provider;

import java.util.List;
import java.util.stream.Stream;

/**
 * A single LLM backend (Ollama, Anthropic, OpenAI, ...). Implementations are
 * discovered by {@code dev.aegis4j.core.provider.ProviderRegistry} either via
 * explicit registration or {@link java.util.ServiceLoader}, and must never be
 * depended on directly by {@code aegis4j-core} — that is what keeps provider
 * swapping a runtime concern instead of a compile-time one.
 *
 * <p><b>Tool-calling support is per-implementation, not part of this
 * contract.</b> {@link CompletionRequest#tools()} may simply be ignored by a
 * given {@code Provider} — as of this writing only
 * {@code OpenAiCompatibleProvider} sends {@code tools} on the wire and
 * returns non-empty {@link CompletionResponse#toolCalls()}; check the
 * concrete implementation's own javadoc before relying on it.
 */
public interface Provider {

    String id();

    CompletionResponse complete(CompletionRequest request);

    Stream<CompletionChunk> stream(CompletionRequest request);

    List<ModelInfo> listModels();
}
