package dev.aegis4j.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aegis4j.mcp.McpException;

/** Shared parsing helpers for incoming JSON-RPC envelopes, used by both transport implementations. */
public final class JsonRpcMessages {

    private JsonRpcMessages() {
    }

    public static boolean isResponse(JsonNode node) {
        return node.has("id") && (node.has("result") || node.has("error"));
    }

    public static long idOf(JsonNode node) {
        return node.get("id").asLong();
    }

    /** Returns the "result" node, or throws {@link McpException} if the envelope carries an "error". */
    public static JsonNode resultOf(JsonNode node) {
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            int code = error.has("code") ? error.get("code").asInt() : 0;
            String message = error.has("message") ? error.get("message").asText() : "MCP error";
            throw new McpException(code, message);
        }
        return node.get("result");
    }
}
