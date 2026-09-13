package dev.aegis4j.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiStreamChunk(String id, String model, List<OpenAiStreamChoice> choices) {
}
