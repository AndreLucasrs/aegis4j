package dev.aegis4j.core.engine;

import dev.aegis4j.api.provider.Message;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code providerId}/{@code model} are optional: {@code null} means "let the
 * configured {@code ModelRouter} decide" rather than a caller mistake — each
 * field is resolved independently, so a caller may pin one and let the other
 * be routed. {@code topK} is optional: {@code null} falls back to the
 * engine's default when a {@code Retriever} is configured.
 */
public record ChatRequest(
        String requestId,
        String userId,
        String providerId,
        String model,
        List<Message> history,
        String userInput,
        Double temperature,
        Integer maxTokens,
        Integer topK,
        Map<String, Object> metadata
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String requestId = UUID.randomUUID().toString();
        private String userId;
        private String providerId;
        private String model;
        private List<Message> history = List.of();
        private String userInput;
        private Double temperature;
        private Integer maxTokens;
        private Integer topK;
        private Map<String, Object> metadata = Map.of();

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder providerId(String providerId) {
            this.providerId = providerId;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder history(List<Message> history) {
            this.history = history;
            return this;
        }

        public Builder userInput(String userInput) {
            this.userInput = userInput;
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

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(
                    requestId, userId, providerId, model, history, userInput, temperature, maxTokens, topK, metadata
            );
        }
    }
}
