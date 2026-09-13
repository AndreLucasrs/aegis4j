package dev.aegis4j.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiChatResponse(String id, String model, List<OpenAiChoice> choices, OpenAiUsage usage) {
}
