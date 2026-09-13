package dev.aegis4j.rag.mcp;

import java.util.Map;

/**
 * Different MCP search tools name their parameters differently
 * ({@code query}/{@code q}/{@code search}, {@code top_k}/{@code limit}/{@code k})
 * — this is the extension point that lets {@link McpToolRetriever} work with
 * any MCP server's search tool without baking one server's schema in.
 */
@FunctionalInterface
public interface McpToolArgumentMapper {

    Map<String, Object> toArguments(String query, int topK);

    static McpToolArgumentMapper defaultMapper() {
        return (query, topK) -> Map.of("query", query, "top_k", topK);
    }
}
