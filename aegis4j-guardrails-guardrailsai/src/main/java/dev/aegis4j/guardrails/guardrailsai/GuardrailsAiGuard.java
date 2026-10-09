package dev.aegis4j.guardrails.guardrailsai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.api.guard.GuardAdapter;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Delegates validation to a <a href="https://github.com/guardrails-ai/guardrails-api">Guardrails AI
 * Server</a> you host yourself, which gives access to the validators of the Guardrails Hub (ML-based
 * jailbreak detection, Presidio PII, toxicity, ...) without bringing Python into the JVM.
 *
 * <p>Per check it sends {@code POST {baseUrl}/guards/{guardName}/validate} with {@code {"llmOutput": text}}
 * (and {@code Authorization: Bearer <apiKey>} when configured) and maps the reply:
 * <ul>
 *   <li>{@code validationPassed=false} → {@link GuardResult.Block} ({@code guardrails-ai-validation-failed});</li>
 *   <li>{@code validationPassed=true} with a different {@code validatedOutput} (a validator with a
 *       {@code fix} action) → {@link GuardResult.Modify} ({@code guardrails-ai-fixed});</li>
 *   <li>otherwise pass.</li>
 * </ul>
 *
 * <p><b>Failure handling.</b> A timeout, connection error, non-2xx status, unparseable body, a missing
 * {@code validationPassed} or a non-empty {@code error} field all count as "the check did not run". With
 * {@link FailMode#FAIL_CLOSED} (the default — this is a security guard) that blocks with
 * {@code guardrails-ai-unavailable}; {@link FailMode#FAIL_OPEN} lets the text through instead. An
 * {@code error} reported by the server is treated as a failure rather than a verdict.
 *
 * <p><b>Privacy and latency.</b> The text is sent to the Guardrails Server, so it must be a server you
 * trust with that data. Hub validators that run ML models add real latency to every request; keep the
 * timeout short and consider {@link #inputOnly}.
 *
 * <p>Neither the checked text nor anything the server returned is ever echoed in a block message or
 * logged. Works as-is inside {@code Aegis4jEngine.Builder#untrustedContentGuards} (that chain calls
 * {@code checkInput}).
 *
 * <p><b>Contract status:</b> the request/response shape follows the Guardrails Server documentation and was
 * checked by hand against a real server (guardrails-ai 0.6.8 + guardrails-api 0.1.0) with a custom
 * validator under the {@code fix}, {@code exception} and {@code noop} failure actions — see
 * {@code GuardrailsAiServerIntegrationTest}. It has <em>not</em> been checked against Guardrails Hub
 * validators, other server versions, or authenticated deployments. The unit tests use a stubbed server.
 */
public final class GuardrailsAiGuard extends GuardAdapter {

    private static final System.Logger LOGGER = System.getLogger(GuardrailsAiGuard.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** What to do when the Guardrails Server cannot give a verdict. */
    public enum FailMode {
        /** Block (default). */
        FAIL_CLOSED,
        /** Let the text through. */
        FAIL_OPEN
    }

    /** Which direction(s) this guard checks. */
    public enum Direction {
        INPUT, OUTPUT, BOTH
    }

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final Direction direction;
    private final FailMode failMode;
    private final String id;

    private GuardrailsAiGuard(Builder b) {
        this.endpoint = endpointFor(b.baseUrl, b.guardName);
        this.apiKey = b.apiKey == null || b.apiKey.isBlank() ? null : b.apiKey;
        this.timeout = Objects.requireNonNull(b.timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.direction = Objects.requireNonNull(b.direction, "direction");
        this.failMode = Objects.requireNonNull(b.failMode, "failMode");
        this.id = "guardrails-ai:" + b.guardName;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public static Builder builder(String baseUrl, String guardName) {
        return new Builder(baseUrl, guardName);
    }

    /** Input-only guard with defaults — the shape to use inside {@code untrustedContentGuards}. */
    public static GuardrailsAiGuard inputOnly(String baseUrl, String guardName) {
        return builder(baseUrl, guardName).direction(Direction.INPUT).build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public GuardResult checkInput(GuardContext ctx, String text) {
        return direction == Direction.OUTPUT ? GuardResult.pass() : validate(text);
    }

    @Override
    public GuardResult checkOutput(GuardContext ctx, String text) {
        return direction == Direction.INPUT ? GuardResult.pass() : validate(text);
    }

    private GuardResult validate(String text) {
        if (text == null) {
            return GuardResult.pass();
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(Map.of("llmOutput", text)), StandardCharsets.UTF_8));
            if (apiKey != null) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (isValidationRejection(response)) {
                return validationFailed();
            }
            if (response.statusCode() / 100 != 2) {
                return failure("HTTP " + response.statusCode());
            }
            return interpret(text, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("interrupted");
        } catch (IOException | RuntimeException e) {
            // Class name only: the message can carry request/response details.
            return failure(e.getClass().getSimpleName());
        }
    }

    private GuardResult interpret(String original, String body) throws IOException {
        JsonNode json = MAPPER.readTree(body);
        if (json == null || !json.isObject()) {
            return failure("unexpected body");
        }
        JsonNode error = json.get("error");
        if (error != null && !error.isNull() && !(error.isTextual() && error.asText().isBlank())) {
            return failure("server reported an error");
        }
        JsonNode passed = json.get("validationPassed");
        if (passed == null || !passed.isBoolean()) {
            return failure("missing validationPassed");
        }
        if (!passed.booleanValue()) {
            return validationFailed();
        }
        JsonNode validated = json.get("validatedOutput");
        if (validated != null && validated.isTextual() && !validated.asText().equals(original)) {
            return GuardResult.modify(validated.asText(), "guardrails-ai-fixed");
        }
        return GuardResult.pass();
    }

    private static GuardResult validationFailed() {
        return GuardResult.block("guardrails-ai-validation-failed",
                "Input did not pass the configured Guardrails AI validation");
    }

    /**
     * A validator configured with {@code on_fail="exception"} is reported by the server not as
     * {@code validationPassed=false} but as HTTP 400 with {@code {"detail":"Validation failed ..."}}
     * (observed with guardrails-ai 0.6.8 / guardrails-api 0.1.0). That is a verdict, not an outage, so it
     * must block even in {@link FailMode#FAIL_OPEN}. Only the fixed prefix of {@code detail} is inspected.
     */
    private static boolean isValidationRejection(HttpResponse<String> response) {
        if (response.statusCode() != 400) {
            return false;
        }
        try {
            JsonNode detail = MAPPER.readTree(response.body()).get("detail");
            return detail != null && detail.isTextual() && detail.asText().startsWith("Validation failed");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private GuardResult failure(String reason) {
        LOGGER.log(System.Logger.Level.WARNING, "Guardrails AI guard ''{0}'' got no verdict ({1}); failMode={2}",
                id, reason, failMode);
        return failMode == FailMode.FAIL_OPEN
                ? GuardResult.pass()
                : GuardResult.block("guardrails-ai-unavailable", "Guardrails AI validation is unavailable");
    }

    private static URI endpointFor(String baseUrl, String guardName) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (guardName == null || guardName.isBlank()) {
            throw new IllegalArgumentException("guardName must not be blank");
        }
        URI base;
        try {
            base = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl is not a valid URL", e);
        }
        String scheme = base.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || base.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL with a host");
        }
        if (base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain a query or fragment");
        }
        String root = base.toString().replaceAll("/+$", "");
        // URLEncoder targets form encoding ('+' for space); in a path segment a space must be %20.
        String segment = URLEncoder.encode(guardName, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(root + "/guards/" + segment + "/validate");
    }

    public static final class Builder {
        private final String baseUrl;
        private final String guardName;
        private String apiKey;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Direction direction = Direction.BOTH;
        private FailMode failMode = FailMode.FAIL_CLOSED;

        private Builder(String baseUrl, String guardName) {
            this.baseUrl = baseUrl;
            this.guardName = guardName;
        }

        /** Sent as {@code Authorization: Bearer <apiKey>}; omitted when null or blank. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Per-request timeout (also the connect timeout). Default {@link #DEFAULT_TIMEOUT}. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder direction(Direction direction) {
            this.direction = direction;
            return this;
        }

        public Builder failMode(FailMode failMode) {
            this.failMode = failMode;
            return this;
        }

        public GuardrailsAiGuard build() {
            return new GuardrailsAiGuard(this);
        }
    }
}
