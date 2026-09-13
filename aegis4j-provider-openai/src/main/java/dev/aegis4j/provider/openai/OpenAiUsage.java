package dev.aegis4j.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiUsage(int promptTokens, int completionTokens, int totalTokens) {
}
