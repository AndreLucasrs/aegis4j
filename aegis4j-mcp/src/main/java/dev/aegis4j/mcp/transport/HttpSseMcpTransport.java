package dev.aegis4j.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.mcp.McpException;
import dev.aegis4j.mcp.McpTransport;
import dev.aegis4j.mcp.protocol.JsonRpcMessages;
import dev.aegis4j.mcp.protocol.JsonRpcNotification;
import dev.aegis4j.mcp.protocol.JsonRpcRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MCP over the "Streamable HTTP" transport: each JSON-RPC request is POSTed;
 * the server may reply with a single {@code application/json} body or a
 * {@code text/event-stream} carrying one or more {@code data: } frames ending
 * with the response to that request. There is no separate long-lived SSE
 * stream for out-of-band server notifications in this v1 — {@link #onServerNotification}
 * only fires for frames observed on an in-flight request's own SSE response.
 */
public final class HttpSseMcpTransport implements McpTransport {

    private final URI endpoint;
    private final Map<String, String> headers;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Consumer<JsonNode>> notificationHandlers = new CopyOnWriteArrayList<>();

    public HttpSseMcpTransport(URI endpoint, Map<String, String> headers, HttpClient httpClient, Duration requestTimeout) {
        this.endpoint = endpoint;
        this.headers = headers;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public JsonNode sendRequest(String method, Object params, long id) {
        HttpResponse<Stream<String>> response = send(JsonRpcRequest.of(id, method, params));
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try (Stream<String> lines = response.body()) {
            if (contentType.contains("text/event-stream")) {
                return readSseForResult(lines, id);
            }
            String json = lines.collect(Collectors.joining());
            return JsonRpcMessages.resultOf(parse(json));
        }
    }

    @Override
    public void sendNotification(String method, Object params) {
        send(JsonRpcNotification.of(method, params)).body().close();
    }

    @Override
    public void onServerNotification(Consumer<JsonNode> handler) {
        notificationHandlers.add(handler);
    }

    @Override
    public void close() {
        // java.net.http.HttpClient manages its own connection pool lifecycle; nothing to release here.
    }

    private HttpResponse<Stream<String>> send(Object envelope) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(envelope)));
        headers.forEach(builder::header);

        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new McpException("Failed to call MCP server at " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted calling MCP server at " + endpoint, e);
        }
    }

    private JsonNode readSseForResult(Stream<String> lines, long id) {
        Iterator<String> iterator = lines.iterator();
        while (iterator.hasNext()) {
            String line = iterator.next();
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).strip();
            if (payload.isEmpty()) {
                continue;
            }
            JsonNode node = parse(payload);
            if (JsonRpcMessages.isResponse(node)) {
                if (JsonRpcMessages.idOf(node) == id) {
                    return JsonRpcMessages.resultOf(node);
                }
            } else {
                notificationHandlers.forEach(handler -> handler.accept(node));
            }
        }
        throw new McpException("MCP SSE stream ended without a response to request id " + id, null);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new McpException("Failed to parse MCP response as JSON: " + json, e);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new McpException("Failed to serialize MCP request", e);
        }
    }
}
