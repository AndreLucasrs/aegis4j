package dev.aegis4j.guardrails.builtin;

import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MaxLengthGuardTest {

    private final GuardContext ctx = new GuardContext("req-1", "user-1", Map.of());

    @Test
    void passesWhenUnderLimit() {
        var guard = MaxLengthGuard.forInput(10);
        assertThat(guard.checkInput(ctx, "short")).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void blocksWhenOverLimit() {
        var guard = MaxLengthGuard.forInput(5);
        GuardResult result = guard.checkInput(ctx, "this is way too long");
        assertThat(result).isInstanceOf(GuardResult.Block.class);
        assertThat(((GuardResult.Block) result).reasonCode()).isEqualTo("input-too-long");
    }

    @Test
    void outputLimitIsIndependentOfInputLimit() {
        var guard = new MaxLengthGuard(5, 100);
        assertThat(guard.checkInput(ctx, "way too long for input")).isInstanceOf(GuardResult.Block.class);
        assertThat(guard.checkOutput(ctx, "way too long for input but fine for output")).isInstanceOf(GuardResult.Pass.class);
    }
}
