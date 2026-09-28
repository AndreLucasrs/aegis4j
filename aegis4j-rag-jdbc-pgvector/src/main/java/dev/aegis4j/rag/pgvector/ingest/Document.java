package dev.aegis4j.rag.pgvector.ingest;

import java.util.Map;

/** A whole, unchunked document loaded from some source (a file, a page, ...), ready for chunking. */
public record Document(String id, String content, Map<String, Object> metadata) {
}
