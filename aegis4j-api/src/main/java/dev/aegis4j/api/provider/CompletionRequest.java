package dev.aegis4j.api.provider;

import java.util.List;
import java.util.Map;

public record CompletionRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Integer maxTokens,
        List<ToolDefinition> tools,
        ResponseFormat responseFormat,
        Map<String, Object> providerOptions
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String model;
        private List<Message> messages = List.of();
        private Double temperature;
        private Integer maxTokens;
        private List<ToolDefinition> tools = List.of();
        private ResponseFormat responseFormat = ResponseFormat.TEXT;
        private Map<String, Object> providerOptions = Map.of();

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        public Builder responseFormat(ResponseFormat responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public Builder providerOptions(Map<String, Object> providerOptions) {
            this.providerOptions = providerOptions;
            return this;
        }

        public CompletionRequest build() {
            return new CompletionRequest(model, messages, temperature, maxTokens, tools, responseFormat, providerOptions);
        }
    }
}
