package dev.aegis4j.rag.pgvector.ingest;

import java.util.List;

/**
 * Loads whole documents from some source, ready for chunking. Kept minimal on
 * purpose: a loader only produces raw document text, splitting is entirely
 * the {@link Chunker}'s job.
 */
public interface DocumentLoader {

    List<Document> load();
}
