package dev.aegis4j.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;

/**
 * A wire-level JSON-RPC 2.0 transport to an MCP server — stdio (a local
 * process) or HTTP/SSE (a remote server). {@link McpClient} is the only
 * caller; this interface knows nothing about MCP methods themselves, only
 * how to move JSON-RPC envelopes back and forth.
 */
public interface McpTransport extends AutoCloseable {

    /** Sends a JSON-RPC request and blocks for its correlated response, returning the "result" node. */
    JsonNode sendRequest(String method, Object params, long id);

    /** Sends a JSON-RPC notification (no response expected). */
    void sendNotification(String method, Object params);

    /** Registers a handler for server-initiated messages (requests/notifications with no matching pending id). */
    void onServerNotification(Consumer<JsonNode> handler);

    @Override
    void close();
}
