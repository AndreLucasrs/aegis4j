package dev.aegis4j.api.provider;

import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> parametersSchema) {
}
