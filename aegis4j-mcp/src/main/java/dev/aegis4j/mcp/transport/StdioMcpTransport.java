package dev.aegis4j.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.mcp.McpException;
import dev.aegis4j.mcp.McpTransport;
import dev.aegis4j.mcp.protocol.JsonRpcMessages;
import dev.aegis4j.mcp.protocol.JsonRpcNotification;
import dev.aegis4j.mcp.protocol.JsonRpcRequest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** MCP over a local process's stdin/stdout — one newline-delimited JSON-RPC envelope per line. */
public final class StdioMcpTransport implements McpTransport {

    private final Process process;
    private final BufferedWriter stdin;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<JsonNode>> notificationHandlers = new CopyOnWriteArrayList<>();
    private final Duration requestTimeout;
    private final Thread readerThread;
    private volatile boolean closed = false;

    public StdioMcpTransport(List<String> command, Map<String, String> env, Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(env);
            this.process = builder.start();
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new McpException("Failed to start MCP stdio process: " + command, e);
        }
        this.readerThread = new Thread(this::readLoop, "aegis4j-mcp-stdio-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        startStderrDrain();
    }

    private void startStderrDrain() {
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // Drained, never parsed as protocol — just prevents the child from blocking on a full stderr pipe.
                }
            } catch (IOException ignored) {
                // Process ended.
            }
        }, "aegis4j-mcp-stdio-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    dispatch(line);
                }
            }
        } catch (IOException ignored) {
            // Process ended or was closed.
        } finally {
            failAllPending();
        }
    }

    private void dispatch(String line) {
        JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (IOException e) {
            return;
        }
        if (JsonRpcMessages.isResponse(node)) {
            CompletableFuture<JsonNode> future = pending.remove(JsonRpcMessages.idOf(node));
            if (future != null) {
                try {
                    future.complete(JsonRpcMessages.resultOf(node));
                } catch (McpException e) {
                    future.completeExceptionally(e);
                }
            }
        } else {
            notificationHandlers.forEach(handler -> handler.accept(node));
        }
    }

    private void failAllPending() {
        pending.values().forEach(f -> f.completeExceptionally(new McpException("MCP stdio transport closed", null)));
        pending.clear();
    }

    @Override
    public synchronized JsonNode sendRequest(String method, Object params, long id) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        writeLine(JsonRpcRequest.of(id, method, params));
        try {
            return future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException("Timed out waiting for MCP response to " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted waiting for MCP response to " + method, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw (cause instanceof McpException mcpException) ? mcpException
                    : new McpException("MCP request failed: " + method, cause);
        }
    }

    @Override
    public synchronized void sendNotification(String method, Object params) {
        writeLine(JsonRpcNotification.of(method, params));
    }

    private void writeLine(Object envelope) {
        try {
            stdin.write(mapper.writeValueAsString(envelope));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new McpException("Failed to write to MCP stdio process", e);
        }
    }

    @Override
    public void onServerNotification(Consumer<JsonNode> handler) {
        notificationHandlers.add(handler);
    }

    @Override
    public void close() {
        closed = true;
        process.destroy();
        readerThread.interrupt();
    }
}
