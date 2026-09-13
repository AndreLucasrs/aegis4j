package dev.aegis4j.mcp.protocol;

public record JsonRpcNotification(String jsonrpc, String method, Object params) {

    public static JsonRpcNotification of(String method, Object params) {
        return new JsonRpcNotification("2.0", method, params);
    }
}
