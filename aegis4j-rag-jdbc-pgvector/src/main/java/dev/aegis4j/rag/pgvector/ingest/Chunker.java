package dev.aegis4j.rag.pgvector.ingest;

import java.util.List;

/**
 * Splits a document's text into retrieval-sized chunks. The contract stays
 * text-in/text-out so a future sentence- or semantic-aware chunker can be
 * swapped in without changing {@link PgVectorIngester} or any caller.
 */
public interface Chunker {

    List<String> chunk(String text);
}
