package dev.aegis4j.rag.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.mcp.McpTransport;

import java.util.function.Consumer;

/** In-process {@link McpTransport} test double for {@code McpToolRetrieverTest} — no I/O. */
final class FakeMcpTransport implements McpTransport {

    private final ObjectMapper mapper = new ObjectMapper();
    private String canned = "{\"content\":[],\"isError\":false}";
    private Object lastParams;

    void respondWith(String json) {
        this.canned = json;
    }

    Object lastParams() {
        return lastParams;
    }

    @Override
    public JsonNode sendRequest(String method, Object params, long id) {
        this.lastParams = params;
        try {
            return mapper.readTree(canned);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void sendNotification(String method, Object params) {
    }

    @Override
    public void onServerNotification(Consumer<JsonNode> handler) {
    }

    @Override
    public void close() {
    }
}
