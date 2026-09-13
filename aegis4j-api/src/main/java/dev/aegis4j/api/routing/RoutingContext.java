package dev.aegis4j.api.routing;

import dev.aegis4j.api.provider.Message;

import java.util.List;
import java.util.Map;

public record RoutingContext(String userInput, List<Message> history, Map<String, Object> requestMetadata) {
}
