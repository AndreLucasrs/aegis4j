package dev.aegis4j.api.mcp;

public sealed interface McpContentBlock {

    record Text(String text) implements McpContentBlock {
    }

    record Resource(String uri, String mimeType, String text) implements McpContentBlock {
    }
}
