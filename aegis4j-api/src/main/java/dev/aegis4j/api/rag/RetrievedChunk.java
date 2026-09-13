package dev.aegis4j.api.rag;

import java.util.Map;

/**
 * {@code score}'s scale is retriever-defined (e.g. cosine similarity for a
 * pgvector-backed {@link Retriever}, a constant for one that can't produce a
 * real relevance score) — never assumed or normalized by the engine.
 */
public record RetrievedChunk(String content, String sourceId, double score, Map<String, Object> metadata) {
}
