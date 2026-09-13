package dev.aegis4j.api.provider;

import java.util.List;
import java.util.stream.Stream;

/**
 * A single LLM backend (Ollama, Anthropic, OpenAI, ...). Implementations are
 * discovered by {@code dev.aegis4j.core.provider.ProviderRegistry} either via
 * explicit registration or {@link java.util.ServiceLoader}, and must never be
 * depended on directly by {@code aegis4j-core} — that is what keeps provider
 * swapping a runtime concern instead of a compile-time one.
 */
public interface Provider {

    String id();

    CompletionResponse complete(CompletionRequest request);

    Stream<CompletionChunk> stream(CompletionRequest request);

    List<ModelInfo> listModels();
}
