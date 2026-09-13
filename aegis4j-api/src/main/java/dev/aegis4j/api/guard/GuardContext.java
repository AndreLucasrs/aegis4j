package dev.aegis4j.api.guard;

import java.util.Map;

public record GuardContext(String requestId, String userId, Map<String, Object> metadata) {
}
