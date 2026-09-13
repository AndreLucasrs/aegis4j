package dev.aegis4j.provider.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record OllamaChatResponseChunk(String model, OllamaMessage message, boolean done) {
}
