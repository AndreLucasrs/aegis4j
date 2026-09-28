package dev.aegis4j.api.guard;

import dev.aegis4j.api.rag.RetrievedChunk;

import java.util.List;
import java.util.Map;

/**
 * {@code metadata} is intentionally a loose {@code Map<String, Object>} bag
 * rather than dedicated typed fields, since (as of this writing) it has
 * exactly one consumer ({@link #retrievedChunks()}) — adding a generic
 * typed-key mechanism (e.g. a {@code TypedKey<T>} companion class) for a
 * single use case would be premature. If a second typed piece of metadata
 * shows up, that is the point to introduce such a mechanism instead of
 * copying the string-key-plus-unchecked-cast pattern below a second time.
 */
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
     * {@link #RETRIEVED_CHUNKS_KEY}. Returns an empty list when absent, not a
     * {@link List}, or a list whose first element isn't a {@link RetrievedChunk}
     * (a best-effort check — a non-empty list of a foreign type could in
     * principle still slip past an empty/heterogeneous prefix, but this catches
     * the realistic "wrong value put under this key" mistake at the source
     * instead of surfacing a {@link ClassCastException} later, deep inside a
     * guard). Guards never need a null/cast check of their own.
     */
    @SuppressWarnings("unchecked")
    public List<RetrievedChunk> retrievedChunks() {
        Object value = metadata.get(RETRIEVED_CHUNKS_KEY);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.get(0) instanceof RetrievedChunk ? (List<RetrievedChunk>) list : List.of();
    }
}
