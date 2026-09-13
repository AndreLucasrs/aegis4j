package dev.aegis4j.rag.mcp;

import dev.aegis4j.api.mcp.McpContentBlock;
import dev.aegis4j.api.mcp.McpToolResult;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.rag.Retriever;
import dev.aegis4j.mcp.McpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapts any MCP server's search-shaped tool into {@link Retriever}. From the
 * engine's point of view there is only ever a {@code Retriever} — it never
 * knows this one is backed by MCP rather than direct JDBC.
 *
 * <p>MCP tool results carry no standardized relevance score, so every chunk's
 * {@link RetrievedChunk#score()} is a constant {@code 1.0} — a known
 * limitation versus a JDBC-backed retriever's real similarity score.
 */
public final class McpToolRetriever implements Retriever {

    private final McpClient mcpClient;
    private final String toolName;
    private final McpToolArgumentMapper argumentMapper;

    public McpToolRetriever(McpClient mcpClient, String toolName, McpToolArgumentMapper argumentMapper) {
        this.mcpClient = mcpClient;
        this.toolName = toolName;
        this.argumentMapper = argumentMapper;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        McpToolResult result = mcpClient.callTool(toolName, argumentMapper.toArguments(query, topK));
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (McpContentBlock block : result.content()) {
            chunks.add(toChunk(block));
        }
        return List.copyOf(chunks);
    }

    private RetrievedChunk toChunk(McpContentBlock block) {
        if (block instanceof McpContentBlock.Resource resource) {
            Map<String, Object> metadata = resource.mimeType() == null ? Map.of() : Map.of("mimeType", resource.mimeType());
            return new RetrievedChunk(resource.text(), resource.uri(), 1.0, metadata);
        }
        McpContentBlock.Text text = (McpContentBlock.Text) block;
        return new RetrievedChunk(text.text(), toolName, 1.0, Map.of());
    }
}
