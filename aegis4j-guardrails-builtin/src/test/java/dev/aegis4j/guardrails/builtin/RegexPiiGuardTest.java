package dev.aegis4j.guardrails.builtin;

import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegexPiiGuardTest {

    private final GuardContext ctx = new GuardContext("req-1", "user-1", Map.of());

    @Test
    void redactsEmail() {
        var guard = RegexPiiGuard.of(PiiPattern.EMAIL);
        GuardResult result = guard.checkOutput(ctx, "contact me at andre@example.com please");
        assertThat(result).isInstanceOf(GuardResult.Modify.class);
        assertThat(((GuardResult.Modify) result).sanitizedText()).isEqualTo("contact me at [EMAIL_REDACTED] please");
    }

    @Test
    void redactsApiKey() {
        var guard = RegexPiiGuard.of(PiiPattern.API_KEY);
        GuardResult result = guard.checkOutput(ctx, "key is sk-ABCDEFGHIJKLMNOPQRSTUVWX end");
        assertThat(result).isInstanceOf(GuardResult.Modify.class);
        assertThat(((GuardResult.Modify) result).sanitizedText()).contains("[API_KEY_REDACTED]");
    }

    @Test
    void passesWhenNoPii() {
        var guard = RegexPiiGuard.allPatterns();
        assertThat(guard.checkOutput(ctx, "nothing sensitive here")).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void doesNotFlagRandomDigitsAsCreditCard() {
        var guard = RegexPiiGuard.of(PiiPattern.CREDIT_CARD);
        GuardResult result = guard.checkOutput(ctx, "order number 1234567890123");
        assertThat(result).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void redactsValidLuhnCreditCard() {
        var guard = RegexPiiGuard.of(PiiPattern.CREDIT_CARD);
        GuardResult result = guard.checkOutput(ctx, "card 4111111111111111 was charged");
        assertThat(result).isInstanceOf(GuardResult.Modify.class);
        assertThat(((GuardResult.Modify) result).sanitizedText()).contains("[CREDIT_CARD_REDACTED]");
    }
}
