package dev.aegis4j.provider.openai;

import java.util.Map;

record OpenAiFunctionDef(String name, String description, Map<String, Object> parameters) {
}
