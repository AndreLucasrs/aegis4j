package dev.aegis4j.api.provider;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTest {

    @Test
    void twoArgConstructorDefaultsToolCallsEmptyAndToolCallIdNull() {
        Message message = new Message(Role.USER, "hi");

        assertThat(message.toolCalls()).isEmpty();
        assertThat(message.toolCallId()).isNull();
    }

    @Test
    void toolResultNormalizesNullContentToEmptyString() {
        Message message = Message.toolResult("call-1", null);

        assertThat(message.content()).isEmpty();
        assertThat(message.toolCallId()).isEqualTo("call-1");
    }

    @Test
    void assistantToolCallAllowsNullContent() {
        ToolCall call = new ToolCall("call-1", "get_weather", "{}");
        Message message = Message.assistantToolCall(null, List.of(call));

        assertThat(message.content()).isNull();
        assertThat(message.toolCalls()).containsExactly(call);
    }

    @Test
    void rejectsNullElementInToolCallsList() {
        assertThatThrownBy(() -> new Message(Role.ASSISTANT, "hi", Arrays.asList((ToolCall) null), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullToolCallsListDefaultsToEmpty() {
        Message message = new Message(Role.ASSISTANT, "hi", null, null);

        assertThat(message.toolCalls()).isEmpty();
    }
}
