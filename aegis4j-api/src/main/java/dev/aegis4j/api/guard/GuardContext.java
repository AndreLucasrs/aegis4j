package dev.aegis4j.api.guard;

import dev.aegis4j.api.rag.RetrievedChunk;

import java.util.List;
import java.util.Map;

public record GuardContext(String requestId, String userId, Map<String, Object> metadata) {

    /**
     * Metadata key under which {@code Aegis4jEngine} stores the
     * {@link RetrievedChunk}s resolved for this turn (empty when no
     * {@code Retriever} is configured or retrieval returned nothing). Guards
     * that need grounding context — e.g. a hallucination/grounding judge —
     * read it via {@link #retrievedChunks()} instead of poking at
     * {@link #metadata()} directly.
     */
    public static final String RETRIEVED_CHUNKS_KEY = "aegis4j.retrievedChunks";

    /**
     * Typed accessor over {@link #metadata()} for the chunks stored under
     * {@link #RETRIEVED_CHUNKS_KEY}. Returns an empty list when absent or of
     * an unexpected type, so guards never need a null/cast check of their own.
     */
    @SuppressWarnings("unchecked")
    public List<RetrievedChunk> retrievedChunks() {
        Object value = metadata.get(RETRIEVED_CHUNKS_KEY);
        return value instanceof List<?> list ? (List<RetrievedChunk>) list : List.of();
    }
}
