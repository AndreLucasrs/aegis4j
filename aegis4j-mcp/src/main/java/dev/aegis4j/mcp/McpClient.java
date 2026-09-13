package dev.aegis4j.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.api.mcp.McpContentBlock;
import dev.aegis4j.api.mcp.McpResourceContent;
import dev.aegis4j.api.mcp.McpResourceDescriptor;
import dev.aegis4j.api.mcp.McpToolDescriptor;
import dev.aegis4j.api.mcp.McpToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-level MCP protocol operations over any {@link McpTransport}. Maps
 * JSON-RPC results into the transport-agnostic value types in
 * {@code dev.aegis4j.api.mcp}.
 */
public final class McpClient implements AutoCloseable {

    private final McpTransport transport;
    private final String clientName;
    private final String clientVersion;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public McpClient(McpTransport transport, String clientName, String clientVersion) {
        this.transport = transport;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
    }

    public McpServerInfo initialize() {
        Map<String, Object> params = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", clientName, "version", clientVersion)
        );
        JsonNode result = transport.sendRequest("initialize", params, nextId());
        transport.sendNotification("notifications/initialized", Map.of());
        return parseServerInfo(result);
    }

    public List<McpToolDescriptor> listTools() {
        JsonNode result = transport.sendRequest("tools/list", Map.of(), nextId());
        List<McpToolDescriptor> tools = new ArrayList<>();
        for (JsonNode toolNode : result.path("tools")) {
            tools.add(new McpToolDescriptor(
                    toolNode.path("name").asText(),
                    toolNode.path("description").asText(""),
                    toMap(toolNode.path("inputSchema"))
            ));
        }
        return List.copyOf(tools);
    }

    public McpToolResult callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> params = Map.of("name", name, "arguments", arguments);
        JsonNode result = transport.sendRequest("tools/call", params, nextId());
        List<McpContentBlock> blocks = new ArrayList<>();
        for (JsonNode blockNode : result.path("content")) {
            blocks.add(toContentBlock(blockNode));
        }
        boolean isError = result.path("isError").asBoolean(false);
        return new McpToolResult(List.copyOf(blocks), isError);
    }

    public List<McpResourceDescriptor> listResources() {
        JsonNode result = transport.sendRequest("resources/list", Map.of(), nextId());
        List<McpResourceDescriptor> resources = new ArrayList<>();
        for (JsonNode node : result.path("resources")) {
            resources.add(new McpResourceDescriptor(
                    node.path("uri").asText(),
                    node.path("name").asText(""),
                    node.path("description").asText(""),
                    node.path("mimeType").asText(null)
            ));
        }
        return List.copyOf(resources);
    }

    public McpResourceContent readResource(String uri) {
        JsonNode result = transport.sendRequest("resources/read", Map.of("uri", uri), nextId());
        JsonNode first = result.path("contents").path(0);
        return new McpResourceContent(
                first.path("uri").asText(uri),
                first.path("mimeType").asText(null),
                first.path("text").asText("")
        );
    }

    /** MCP's third capability (prompts) is out of scope until v0.3. */
    public List<McpPromptDescriptor> listPrompts() {
        throw new UnsupportedOperationException("MCP prompts are not implemented until v0.3");
    }

    @Override
    public void close() {
        transport.close();
    }

    private long nextId() {
        return idGenerator.getAndIncrement();
    }

    private McpServerInfo parseServerInfo(JsonNode result) {
        JsonNode serverInfo = result.path("serverInfo");
        JsonNode capabilities = result.path("capabilities");
        return new McpServerInfo(
                serverInfo.path("name").asText(""),
                serverInfo.path("version").asText(""),
                new McpServerCapabilities(
                        capabilities.has("tools"),
                        capabilities.has("resources"),
                        capabilities.has("prompts")
                )
        );
    }

    private McpContentBlock toContentBlock(JsonNode node) {
        String type = node.path("type").asText("text");
        if ("resource".equals(type)) {
            JsonNode resource = node.path("resource");
            return new McpContentBlock.Resource(
                    resource.path("uri").asText(""),
                    resource.path("mimeType").asText(null),
                    resource.path("text").asText("")
            );
        }
        return new McpContentBlock.Text(node.path("text").asText(""));
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }
}
