package dev.aegis4j.mcp.protocol;

public record JsonRpcRequest(String jsonrpc, long id, String method, Object params) {

    public static JsonRpcRequest of(long id, String method, Object params) {
        return new JsonRpcRequest("2.0", id, method, params);
    }
}
