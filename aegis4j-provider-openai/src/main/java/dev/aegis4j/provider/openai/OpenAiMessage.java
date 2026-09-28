package dev.aegis4j.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiMessage(String role, String content, List<OpenAiToolCall> toolCalls, String toolCallId) {

    OpenAiMessage(String role, String content) {
        this(role, content, null, null);
    }
}
