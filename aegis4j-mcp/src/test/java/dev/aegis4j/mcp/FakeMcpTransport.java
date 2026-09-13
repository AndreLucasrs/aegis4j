package dev.aegis4j.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** In-process {@link McpTransport} test double — no I/O — for testing {@link McpClient}'s method/JSON mapping. */
final class FakeMcpTransport implements McpTransport {

    record SentRequest(String method, Object params, long id) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> jsonResultsByMethod = new HashMap<>();
    private final List<SentRequest> sentRequests = new ArrayList<>();

    void respondTo(String method, String jsonResult) {
        jsonResultsByMethod.put(method, jsonResult);
    }

    List<SentRequest> sentRequests() {
        return List.copyOf(sentRequests);
    }

    @Override
    public JsonNode sendRequest(String method, Object params, long id) {
        sentRequests.add(new SentRequest(method, params, id));
        String json = jsonResultsByMethod.get(method);
        if (json == null) {
            throw new McpException(-1, "No fake response configured for method " + method);
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new McpException("Failed to parse fake response", e);
        }
    }

    @Override
    public void sendNotification(String method, Object params) {
        sentRequests.add(new SentRequest(method, params, -1));
    }

    @Override
    public void onServerNotification(Consumer<JsonNode> handler) {
        // Not exercised by this fake.
    }

    @Override
    public void close() {
    }
}
