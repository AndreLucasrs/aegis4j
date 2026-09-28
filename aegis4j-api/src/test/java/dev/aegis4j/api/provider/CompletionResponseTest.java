package dev.aegis4j.api.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionResponseTest {

    @Test
    void normalizesNullToolCallsToEmptyList() {
        CompletionResponse response = new CompletionResponse(
                "id", "model", "content", FinishReason.STOP, Usage.UNKNOWN, null
        );

        assertThat(response.toolCalls()).isEmpty();
    }
}
