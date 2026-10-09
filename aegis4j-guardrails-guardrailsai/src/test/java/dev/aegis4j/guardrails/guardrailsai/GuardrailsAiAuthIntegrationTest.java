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
 * Checks that our client sends {@code Authorization: Bearer} and treats a 401 as "unavailable".
 * Runs against a real Guardrails Server behind {@code src/test/resources/auth-proxy.py}, which enforces the token
 * (the server itself does not authenticate). Skipped unless {@code GUARDRAILS_AUTH_URL} and
 * {@code GUARDRAILS_AUTH_TOKEN} are set; start the proxy with {@code python auth-proxy.py <port> <upstream-port> <token>}.
 */
@EnabledIfEnvironmentVariable(named = "GUARDRAILS_AUTH_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GUARDRAILS_AUTH_TOKEN", matches = ".+")
class GuardrailsAiAuthIntegrationTest {

    private final GuardContext ctx = new GuardContext("req-1", "user-1", Map.of());
    private final String url = System.getenv("GUARDRAILS_AUTH_URL");
    private final String token = System.getenv("GUARDRAILS_AUTH_TOKEN");

    private GuardrailsAiGuard guard(String apiKey, FailMode mode) {
        var builder = GuardrailsAiGuard.builder(url, "smoke-fix").failMode(mode).timeout(Duration.ofSeconds(20));
        return (apiKey == null ? builder : builder.apiKey(apiKey)).build();
    }

    @Test
    void correctTokenIsAccepted() {
        GuardResult result = guard(token, FailMode.FAIL_CLOSED).checkInput(ctx, "has a BADWORD");

        assertThat(result).isInstanceOf(GuardResult.Modify.class);
    }

    @Test
    void missingOrWrongTokenFailsClosedAsUnavailable() {
        for (String apiKey : new String[] {null, "wrong-token"}) {
            GuardResult result = guard(apiKey, FailMode.FAIL_CLOSED).checkInput(ctx, "has a BADWORD");

            assertThat(result).isInstanceOf(GuardResult.Block.class);
            assertThat(((GuardResult.Block) result).reasonCode()).isEqualTo("guardrails-ai-unavailable");
        }
    }

    @Test
    void failOpenLetsTheTextThroughWhenAuthIsRejected() {
        assertThat(guard("wrong-token", FailMode.FAIL_OPEN).checkInput(ctx, "has a BADWORD")).isInstanceOf(GuardResult.Pass.class);
    }
}
