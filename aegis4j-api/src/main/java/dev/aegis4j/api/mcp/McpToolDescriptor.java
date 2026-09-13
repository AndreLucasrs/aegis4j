package dev.aegis4j.api.mcp;

import java.util.Map;

public record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
}
