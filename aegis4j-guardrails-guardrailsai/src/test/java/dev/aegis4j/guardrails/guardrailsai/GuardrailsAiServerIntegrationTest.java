package dev.aegis4j.guardrails.guardrailsai;

import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
import dev.aegis4j.guardrails.guardrailsai.GuardrailsAiGuard.FailMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a <b>real</b> Guardrails Server; skipped unless {@code GUARDRAILS_SERVER_URL} is set.
 * Start one with {@code src/test/resources/guardrails-smoke-config.py} (instructions in that file).
 */
@EnabledIfEnvironmentVariable(named = "GUARDRAILS_SERVER_URL", matches = ".+")
class GuardrailsAiServerIntegrationTest {

    private final GuardContext ctx = new GuardContext("req-1", "user-1", Map.of());
    private final String baseUrl = System.getenv("GUARDRAILS_SERVER_URL");

    private GuardrailsAiGuard guard(String name, FailMode mode) {
        return GuardrailsAiGuard.builder(baseUrl, name).failMode(mode).timeout(Duration.ofSeconds(20)).build();
    }

    @Test
    void cleanTextPassesOnEveryFailureAction() {
        for (String name : new String[] {"smoke-fix", "smoke-exception", "smoke-noop"}) {
            assertThat(guard(name, FailMode.FAIL_CLOSED).checkInput(ctx, "hello world")).isInstanceOf(GuardResult.Pass.class);
        }
    }

    @Test
    void fixActionBecomesModify() {
        GuardResult result = guard("smoke-fix", FailMode.FAIL_CLOSED).checkInput(ctx, "this has a BADWORD inside");

        assertThat(result).isInstanceOf(GuardResult.Modify.class);
        assertThat(((GuardResult.Modify) result).sanitizedText()).isEqualTo("this has a <redacted> inside");
    }

    @Test
    void noopActionBecomesBlockViaValidationPassedFalse() {
        GuardResult result = guard("smoke-noop", FailMode.FAIL_CLOSED).checkInput(ctx, "this has a BADWORD inside");

        assertThat(result).isInstanceOf(GuardResult.Block.class);
        assertThat(((GuardResult.Block) result).reasonCode()).isEqualTo("guardrails-ai-validation-failed");
    }

    @Test
    void exceptionActionBecomesBlockEvenWhenFailingOpen() {
        GuardResult result = guard("smoke-exception", FailMode.FAIL_OPEN).checkInput(ctx, "this has a BADWORD inside");

        assertThat(result).isInstanceOf(GuardResult.Block.class);
        assertThat(((GuardResult.Block) result).reasonCode()).isEqualTo("guardrails-ai-validation-failed");
    }

    @Test
    void unknownGuardIsUnavailableNotAVerdict() {
        GuardResult closed = guard("does-not-exist", FailMode.FAIL_CLOSED).checkInput(ctx, "x");

        assertThat(closed).isInstanceOf(GuardResult.Block.class);
        assertThat(((GuardResult.Block) closed).reasonCode()).isEqualTo("guardrails-ai-unavailable");
    }
}
