package dev.aegis4j.api.rag;

import java.util.List;

/**
 * A pluggable source of retrieval-augmented context — direct pgvector JDBC,
 * an MCP server's search tool, or anything else. The engine only ever talks
 * to this interface; it never knows or cares which backend is underneath.
 */
public interface Retriever {

    List<RetrievedChunk> retrieve(String query, int topK);
}
