package dev.aegis4j.guardrails.guardrailsai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;
import dev.aegis4j.guardrails.guardrailsai.GuardrailsAiGuard.Direction;
import dev.aegis4j.guardrails.guardrailsai.GuardrailsAiGuard.FailMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardrailsAiGuardTest {

    private static final String SECRET_TEXT = "my-very-secret-user-text";

    private final GuardContext ctx = new GuardContext("req-1", "user-1", Map.of());
    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private String baseUrl() {
        return "http://localhost:" + server.port();
    }

    private void stubValidate(String guard, int status, String body) {
        server.stubFor(post(urlEqualTo("/guards/" + guard + "/validate"))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private GuardrailsAiGuard.Builder builder(String guard) {
        return GuardrailsAiGuard.builder(baseUrl(), guard).timeout(Duration.ofSeconds(2));
    }

    private static String reasonOf(GuardResult result) {
        assertThat(result).isInstanceOf(GuardResult.Block.class);
        return ((GuardResult.Block) result).reasonCode();
    }

    // --- verdicts ----------------------------------------------------------

    @Test
    void passesWhenValidationPassedAndOutputUnchanged() {
        stubValidate("g", 200, "{\"validationPassed\":true,\"validatedOutput\":\"hello\",\"rawLlmOutput\":\"hello\"}");

        assertThat(builder("g").build().checkInput(ctx, "hello")).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void sendsLlmOutputAsJsonBody() {
        stubValidate("g", 200, "{\"validationPassed\":true}");

        builder("g").build().checkInput(ctx, "hi \"quoted\"\nline");

        server.verify(postRequestedFor(urlEqualTo("/guards/g/validate"))
                .withRequestBody(equalToJson("{\"llmOutput\":\"hi \\\"quoted\\\"\\nline\"}")));
    }

    @Test
    void blocksWhenValidationFailed() {
        stubValidate("g", 200, "{\"validationPassed\":false,\"rawLlmOutput\":\"x\"}");

        GuardResult result = builder("g").build().checkInput(ctx, "x");

        assertThat(reasonOf(result)).isEqualTo("guardrails-ai-validation-failed");
    }

    @Test
    void modifiesWhenValidatorFixedTheText() {
        stubValidate("g", 200, "{\"validationPassed\":true,\"validatedOutput\":\"call me at <PHONE>\"}");

        GuardResult result = builder("g").build().checkInput(ctx, "call me at 555-1234");

        assertThat(result).isInstanceOf(GuardResult.Modify.class);
        GuardResult.Modify modify = (GuardResult.Modify) result;
        assertThat(modify.sanitizedText()).isEqualTo("call me at <PHONE>");
        assertThat(modify.reasonCode()).isEqualTo("guardrails-ai-fixed");
    }

    @Test
    void passesWhenValidatedOutputIsMissingOrNull() {
        stubValidate("g", 200, "{\"validationPassed\":true,\"validatedOutput\":null}");

        assertThat(builder("g").build().checkInput(ctx, "x")).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void nullTextPassesWithoutCallingTheServer() {
        assertThat(builder("g").build().checkInput(ctx, null)).isInstanceOf(GuardResult.Pass.class);
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    // --- failures ----------------------------------------------------------

    @Test
    void serverErrorFailsClosedByDefault() {
        stubValidate("g", 500, "{\"detail\":\"boom\"}");

        assertThat(reasonOf(builder("g").build().checkInput(ctx, "x"))).isEqualTo("guardrails-ai-unavailable");
    }

    @Test
    void clientErrorFailsClosedByDefault() {
        stubValidate("g", 404, "{\"detail\":\"no such guard\"}");

        assertThat(reasonOf(builder("g").build().checkInput(ctx, "x"))).isEqualTo("guardrails-ai-unavailable");
    }

    @Test
    void http400ValidationFailedIsAVerdictAndBlocksEvenWhenFailingOpen() {
        // What a real server returns for a validator with on_fail="exception".
        stubValidate("g", 400, "{\"detail\":\"Validation failed for field with errors: contains badword\"}");

        GuardResult open = builder("g").failMode(FailMode.FAIL_OPEN).build().checkInput(ctx, "x");
        GuardResult closed = builder("g").build().checkInput(ctx, "x");

        assertThat(reasonOf(open)).isEqualTo("guardrails-ai-validation-failed");
        assertThat(reasonOf(closed)).isEqualTo("guardrails-ai-validation-failed");
        assertThat(((GuardResult.Block) open).message()).doesNotContain("badword");
    }

    @Test
    void other400ResponsesAreNotMistakenForAVerdict() {
        stubValidate("g", 400, "{\"detail\":\"Bad request\"}");
        assertThat(builder("g").failMode(FailMode.FAIL_OPEN).build().checkInput(ctx, "x")).isInstanceOf(GuardResult.Pass.class);
        assertThat(reasonOf(builder("g").build().checkInput(ctx, "x"))).isEqualTo("guardrails-ai-unavailable");

        stubValidate("g", 400, "not json");
        assertThat(reasonOf(builder("g").build().checkInput(ctx, "x"))).isEqualTo("guardrails-ai-unavailable");
    }

    @Test
    void invalidJsonFailsClosed() {
        stubValidate("g", 200, "<html>not json</html>");

        assertThat(reasonOf(builder("g").build().checkInput(ctx, "x"))).isEqualTo("guardrails-ai-unavailable");
    }

    @Test
    void jsonThatIsNotAnObjectFailsClosed() {
        stubValidate("g", 200, "[true]");

        assertThat(reasonOf(builder("g").build().checkInput(ctx, "x"))).isEqualTo("guardrails-ai-unavailable");
    }

    @Test
    void missingValidationPassedFailsClosed() {
        stubValidate("g", 200, "{\"validatedOutput\":\"x\"}");

        assertThat(reasonOf(builder("g").build().checkInput(ctx, "x"))).isEqualTo("guardrails-ai-unavailable");
    }

    @Test
    void errorFieldFailsClosedEvenIfValidationPassedIsTrue() {
        stubValidate("g", 200, "{\"validationPassed\":true,\"error\":\"validator crashed\"}");

        assertThat(reasonOf(builder("g").build().checkInput(ctx, "x"))).isEqualTo("guardrails-ai-unavailable");
    }

    @Test
    void nullOrEmptyErrorFieldIsNotAFailure() {
        stubValidate("g", 200, "{\"validationPassed\":true,\"error\":null}");
        assertThat(builder("g").build().checkInput(ctx, "x")).isInstanceOf(GuardResult.Pass.class);

        stubValidate("g", 200, "{\"validationPassed\":true,\"error\":\"\"}");
        assertThat(builder("g").build().checkInput(ctx, "x")).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void timeoutFailsClosed() {
        server.stubFor(post(urlEqualTo("/guards/g/validate"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000).withBody("{\"validationPassed\":true}")));

        GuardResult result = GuardrailsAiGuard.builder(baseUrl(), "g").timeout(Duration.ofMillis(300)).build()
                .checkInput(ctx, "x");

        assertThat(reasonOf(result)).isEqualTo("guardrails-ai-unavailable");
    }

    @Test
    void connectionRefusedFailsClosed() {
        int deadPort = server.port();
        server.stop();

        GuardResult result = GuardrailsAiGuard.builder("http://localhost:" + deadPort, "g")
                .timeout(Duration.ofMillis(500)).build().checkInput(ctx, "x");

        assertThat(reasonOf(result)).isEqualTo("guardrails-ai-unavailable");
    }

    @Test
    void failOpenLetsTextThroughOnEveryKindOfFailure() {
        GuardrailsAiGuard guard = builder("g").failMode(FailMode.FAIL_OPEN).build();

        stubValidate("g", 500, "{}");
        assertThat(guard.checkInput(ctx, "x")).isInstanceOf(GuardResult.Pass.class);

        stubValidate("g", 200, "garbage");
        assertThat(guard.checkInput(ctx, "x")).isInstanceOf(GuardResult.Pass.class);

        stubValidate("g", 200, "{\"validationPassed\":true,\"error\":\"oops\"}");
        assertThat(guard.checkInput(ctx, "x")).isInstanceOf(GuardResult.Pass.class);
    }

    @Test
    void failOpenStillBlocksAnActualValidationFailure() {
        stubValidate("g", 200, "{\"validationPassed\":false}");

        GuardResult result = builder("g").failMode(FailMode.FAIL_OPEN).build().checkInput(ctx, "x");

        assertThat(reasonOf(result)).isEqualTo("guardrails-ai-validation-failed");
    }

    // --- request details ---------------------------------------------------

    @Test
    void sendsBearerTokenWhenConfigured() {
        stubValidate("g", 200, "{\"validationPassed\":true}");

        builder("g").apiKey("s3cr3t").build().checkInput(ctx, "x");

        server.verify(postRequestedFor(urlEqualTo("/guards/g/validate"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer s3cr3t")));
    }

    @Test
    void omitsAuthorizationHeaderWithoutApiKey() {
        stubValidate("g", 200, "{\"validationPassed\":true}");

        builder("g").build().checkInput(ctx, "x");

        server.verify(postRequestedFor(urlEqualTo("/guards/g/validate"))
                .withoutHeader("Authorization"));
    }

    @Test
    void guardNameIsUrlEncodedInThePath() {
        server.stubFor(post(urlEqualTo("/guards/my%20guard%2Fv2%3Fx/validate"))
                .willReturn(aResponse().withStatus(200).withBody("{\"validationPassed\":false}")));

        GuardResult result = builder("my guard/v2?x").build().checkInput(ctx, "x");

        assertThat(reasonOf(result)).isEqualTo("guardrails-ai-validation-failed");
    }

    @Test
    void trailingSlashOnBaseUrlIsTolerated() {
        stubValidate("g", 200, "{\"validationPassed\":false}");

        GuardResult result = GuardrailsAiGuard.builder(baseUrl() + "//", "g").build().checkInput(ctx, "x");

        assertThat(reasonOf(result)).isEqualTo("guardrails-ai-validation-failed");
    }

    // --- directions --------------------------------------------------------

    @Test
    void inputOnlyDoesNotCheckOutput() {
        stubValidate("g", 200, "{\"validationPassed\":false}");
        GuardrailsAiGuard guard = GuardrailsAiGuard.inputOnly(baseUrl(), "g");

        assertThat(guard.checkOutput(ctx, "x")).isInstanceOf(GuardResult.Pass.class);
        assertThat(guard.checkInput(ctx, "x")).isInstanceOf(GuardResult.Block.class);
        assertThat(server.getAllServeEvents()).hasSize(1);
    }

    @Test
    void outputOnlyDoesNotCheckInput() {
        stubValidate("g", 200, "{\"validationPassed\":false}");
        GuardrailsAiGuard guard = builder("g").direction(Direction.OUTPUT).build();

        assertThat(guard.checkInput(ctx, "x")).isInstanceOf(GuardResult.Pass.class);
        assertThat(guard.checkOutput(ctx, "x")).isInstanceOf(GuardResult.Block.class);
    }

    @Test
    void defaultChecksBothDirections() {
        stubValidate("g", 200, "{\"validationPassed\":false}");
        GuardrailsAiGuard guard = builder("g").build();

        assertThat(guard.checkInput(ctx, "x")).isInstanceOf(GuardResult.Block.class);
        assertThat(guard.checkOutput(ctx, "x")).isInstanceOf(GuardResult.Block.class);
    }

    @Test
    void idIncludesTheGuardName() {
        assertThat(builder("pii-check").build().id()).isEqualTo("guardrails-ai:pii-check");
    }

    // --- information hygiene -----------------------------------------------

    @Test
    void blockMessagesNeverLeakTheTextOrServerContent() {
        stubValidate("g", 200, "{\"validationPassed\":false,\"rawLlmOutput\":\"" + SECRET_TEXT
                + "\",\"reask\":{\"incorrect_value\":\"SERVER-DETAIL\"}}");
        GuardResult.Block failed = (GuardResult.Block) builder("g").build().checkInput(ctx, SECRET_TEXT);

        stubValidate("g", 500, "{\"detail\":\"SERVER-DETAIL " + SECRET_TEXT + "\"}");
        GuardResult.Block unavailable = (GuardResult.Block) builder("g").build().checkInput(ctx, SECRET_TEXT);

        for (GuardResult.Block block : new GuardResult.Block[] {failed, unavailable}) {
            assertThat(block.message()).doesNotContain(SECRET_TEXT).doesNotContain("SERVER-DETAIL");
            assertThat(block.reasonCode()).doesNotContain(SECRET_TEXT);
        }
    }

    // --- configuration validation -----------------------------------------

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> GuardrailsAiGuard.builder(null, "g").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailsAiGuard.builder(" ", "g").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailsAiGuard.builder("ftp://host", "g").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailsAiGuard.builder("localhost:8000", "g").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailsAiGuard.builder("http://host?x=1", "g").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailsAiGuard.builder("not a url", "g").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailsAiGuard.builder("http://host", " ").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuardrailsAiGuard.builder("http://host", "g").timeout(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
