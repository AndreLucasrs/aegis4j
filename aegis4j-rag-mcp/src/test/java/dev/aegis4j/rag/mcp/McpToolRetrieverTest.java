package dev.aegis4j.rag.mcp;

import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.mcp.McpClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRetrieverTest {

    @Test
    void mapsTextContentBlocksToRetrievedChunksAndUsesArgumentMapper() {
        FakeMcpTransport transport = new FakeMcpTransport();
        transport.respondWith("""
                {"content":[{"type":"text","text":"a chunk of context"}],"isError":false}
                """);
        McpClient client = new McpClient(transport, "test", "1.0");

        McpToolRetriever retriever = new McpToolRetriever(client, "search", McpToolArgumentMapper.defaultMapper());
        List<RetrievedChunk> chunks = retriever.retrieve("weather", 3);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("a chunk of context");
        assertThat(chunks.get(0).sourceId()).isEqualTo("search");

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) transport.lastParams();
        assertThat(params).containsEntry("name", "search");

        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
        assertThat(arguments).containsEntry("query", "weather").containsEntry("top_k", 3);
    }

    @Test
    void mapsResourceContentBlocksWithMimeTypeMetadata() {
        FakeMcpTransport transport = new FakeMcpTransport();
        transport.respondWith("""
                {"content":[{"type":"resource","resource":{"uri":"doc://1","mimeType":"text/plain","text":"resource body"}}],"isError":false}
                """);
        McpClient client = new McpClient(transport, "test", "1.0");

        McpToolRetriever retriever = new McpToolRetriever(client, "search", McpToolArgumentMapper.defaultMapper());
        List<RetrievedChunk> chunks = retriever.retrieve("query", 3);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("resource body");
        assertThat(chunks.get(0).sourceId()).isEqualTo("doc://1");
        assertThat(chunks.get(0).metadata()).containsEntry("mimeType", "text/plain");
    }
}
