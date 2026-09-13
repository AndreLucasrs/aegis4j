package dev.aegis4j.provider.ollama;

import java.util.List;
import java.util.Map;

record OllamaChatRequest(String model, List<OllamaMessage> messages, boolean stream, Map<String, Object> options) {
}
