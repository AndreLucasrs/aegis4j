package dev.aegis4j.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiToolCall(String id, String type, OpenAiFunctionCall function) {

    static OpenAiToolCall function(String id, OpenAiFunctionCall function) {
        return new OpenAiToolCall(id, "function", function);
    }
}
