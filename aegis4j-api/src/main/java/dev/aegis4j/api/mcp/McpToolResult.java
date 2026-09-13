package dev.aegis4j.api.mcp;

import java.util.List;

public record McpToolResult(List<McpContentBlock> content, boolean isError) {
}
