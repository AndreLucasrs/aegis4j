package dev.aegis4j.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.core.engine.Aegis4jEngine;
import dev.aegis4j.core.guard.GuardChain;
import dev.aegis4j.core.provider.ProviderRegistry;
import dev.aegis4j.core.routing.ModelRouter;
import dev.aegis4j.core.skill.SkillRegistry;
import dev.aegis4j.guardrails.builtin.MaxLengthGuard;
import dev.aegis4j.guardrails.builtin.RegexPiiGuard;
import dev.aegis4j.provider.ollama.OllamaProvider;
import dev.aegis4j.skills.markdown.MarkdownSkillLoader;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the full v0.1 pipeline end to end against a WireMock-faked Ollama
 * backend (never a real network call, never a real Ollama instance):
 * input guard -> skill catalog/activation -> provider call -> output guard.
 */
class Aegis4jServerE2ETest {

    @TempDir
    private Path tempDir;

    private WireMockServer wireMock;
    private Javalin app;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"llama3","message":{"role":"assistant","content":"my email on file is a@b.com"},"done":true}
                        """)));

        Files.writeString(tempDir.resolve("weather-explainer.md"), """
                ---
                name: weather-explainer
                description: Explains weather concepts in simple, non-technical terms.
                triggers: [weather, forecast, clima]
                ---
                FULL_WEATHER_SKILL_BODY
                """);

        app = Aegis4jServerApp.createApp(buildFullPipelineEngine(), OllamaProvider.ID, null);
        app.start(0);
    }

    /**
     * Builds the same engine/guard/skill pipeline as {@link #setUp()} (input guard,
     * PII redaction, skill catalog) so the auth tests exercise the full production
     * wiring rather than a stripped-down one, and only vary the server's api key.
     */
    private Aegis4jEngine buildFullPipelineEngine() {
        OllamaProvider ollama = new OllamaProvider(
                "http://localhost:" + wireMock.port(), HttpClient.newHttpClient(), Duration.ofSeconds(5)
        );
        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.register(ollama);

        SkillRegistry skillRegistry = SkillRegistry.inMemory();
        new MarkdownSkillLoader().loadDirectory(tempDir).forEach(skillRegistry::register);

        return Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(GuardChain.of(MaxLengthGuard.forInput(4000), RegexPiiGuard.allPatterns()))
                .skillRegistry(skillRegistry)
                .build();
    }

    @AfterEach
    void tearDown() {
        app.stop();
        wireMock.stop();
    }

    @Test
    void redactsPiiInResponseAndActivatesSkillOnlyWhenTriggered() throws Exception {
        HttpResponse<String> response = postChatCompletion("""
                {"model":"llama3","messages":[{"role":"user","content":"what is the weather like, my email is a@b.com"}]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = mapper.readTree(response.body());
        String content = body.at("/choices/0/message/content").asText();
        assertThat(content).isEqualTo("my email on file is [EMAIL_REDACTED]");

        wireMock.verify(postRequestedFor(urlEqualTo("/api/chat")).withRequestBody(containing("FULL_WEATHER_SKILL_BODY")));
    }

    @Test
    void doesNotActivateSkillWhenTriggerKeywordAbsent() throws Exception {
        postChatCompletion("""
                {"model":"llama3","messages":[{"role":"user","content":"unrelated question"}]}
                """);

        var matchingRequests = wireMock.findAll(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(containing("FULL_WEATHER_SKILL_BODY")));
        assertThat(matchingRequests).isEmpty();
    }

    @Test
    void resolvesModelFromRouterWhenOmittedInRequest() throws Exception {
        OllamaProvider ollama = new OllamaProvider(
                "http://localhost:" + wireMock.port(), HttpClient.newHttpClient(), Duration.ofSeconds(5)
        );
        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.register(ollama);

        ModelRouter router = new ModelRouter(
                List.of(ctx -> ctx.userInput().contains("code")
                        ? Optional.of(new RouteTarget(OllamaProvider.ID, "qwen2.5-coder:7b"))
                        : Optional.empty()),
                new RouteTarget(OllamaProvider.ID, "llama3.1:8b")
        );

        Aegis4jEngine routedEngine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(GuardChain.of(MaxLengthGuard.forInput(4000)))
                .modelRouter(router)
                .build();

        Javalin routedApp = Aegis4jServerApp.createApp(routedEngine, OllamaProvider.ID, null);
        routedApp.start(0);
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + routedApp.port() + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"messages":[{"role":"user","content":"help me fix this code bug"}]}
                            """))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            wireMock.verify(postRequestedFor(urlEqualTo("/api/chat")).withRequestBody(containing("qwen2.5-coder:7b")));
        } finally {
            routedApp.stop();
        }
    }

    @Test
    void streamsSseChunksAndTerminatesWithDoneWhenStreamTrue() throws Exception {
        WireMockServer streamingWireMock = new WireMockServer(options().dynamicPort());
        streamingWireMock.start();
        streamingWireMock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"llama3","message":{"role":"assistant","content":"hel"},"done":false}
                        {"model":"llama3","message":{"role":"assistant","content":"lo"},"done":false}
                        {"model":"llama3","message":{"role":"assistant","content":""},"done":true}
                        """)));

        OllamaProvider ollama = new OllamaProvider(
                "http://localhost:" + streamingWireMock.port(), HttpClient.newHttpClient(), Duration.ofSeconds(5)
        );
        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.register(ollama);

        Aegis4jEngine streamingEngine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(GuardChain.of(MaxLengthGuard.forInput(4000)))
                .build();

        Javalin streamingApp = Aegis4jServerApp.createApp(streamingEngine, OllamaProvider.ID, null);
        streamingApp.start(0);
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + streamingApp.port() + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"model":"llama3","messages":[{"role":"user","content":"hi"}],"stream":true}
                            """))
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(
                    contentType -> assertThat(contentType).startsWith("text/event-stream"));

            List<String> dataLines;
            try (Stream<String> lines = response.body()) {
                dataLines = lines.filter(line -> line.startsWith("data: ")).map(line -> line.substring("data: ".length())).toList();
            }

            assertThat(dataLines).hasSize(4);
            assertThat(dataLines.get(3)).isEqualTo("[DONE]");

            JsonNode first = mapper.readTree(dataLines.get(0));
            assertThat(first.at("/object").asText()).isEqualTo("chat.completion.chunk");
            assertThat(first.at("/choices/0/delta/role").asText()).isEqualTo("assistant");
            assertThat(first.at("/choices/0/delta/content").asText()).isEqualTo("hel");
            assertThat(first.at("/choices/0/finish_reason").isNull()).isTrue();

            JsonNode second = mapper.readTree(dataLines.get(1));
            assertThat(second.at("/choices/0/delta/role").isMissingNode()).isTrue();
            assertThat(second.at("/choices/0/delta/content").asText()).isEqualTo("lo");

            JsonNode last = mapper.readTree(dataLines.get(2));
            assertThat(last.at("/choices/0/delta/content").isMissingNode()).isTrue();
            assertThat(last.at("/choices/0/finish_reason").asText()).isEqualTo("stop");
        } finally {
            streamingApp.stop();
            streamingWireMock.stop();
        }
    }

    @Test
    void acceptsRequestWithMatchingBearerTokenWhenApiKeyConfigured() throws Exception {
        Javalin authApp = startAppWithApiKey("secret-key");
        try {
            HttpResponse<String> response = postChatCompletion(authApp, "Bearer secret-key", """
                    {"model":"llama3","messages":[{"role":"user","content":"hello"}]}
                    """);

            assertThat(response.statusCode()).isEqualTo(200);
        } finally {
            authApp.stop();
        }
    }

    @Test
    void returnsUnifiedErrorShapeWhenMessagesEmpty() throws Exception {
        HttpResponse<String> response = postChatCompletion("""
                {"model":"llama3","messages":[]}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.at("/error/code").asText()).isEqualTo("invalid_request");
        assertThat(body.at("/error/message").asText()).isEqualTo("messages must not be empty");
    }

    @Test
    void streamingReturnsUnifiedJsonErrorWhenProviderUnresolvable() throws Exception {
        ProviderRegistry emptyRegistry = new ProviderRegistry();
        Aegis4jEngine engine = Aegis4jEngine.builder()
                .providerRegistry(emptyRegistry)
                .guardChain(GuardChain.of(MaxLengthGuard.forInput(4000)))
                .build();

        Javalin brokenApp = Aegis4jServerApp.createApp(engine, "missing-provider", null);
        brokenApp.start(0);
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + brokenApp.port() + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"model":"llama3","messages":[{"role":"user","content":"hi"}],"stream":true}
                            """))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(400);
            JsonNode body = mapper.readTree(response.body());
            assertThat(body.at("/error/code").asText()).isEqualTo("unknown_provider");
            assertThat(body.at("/error/message").asText()).contains("missing-provider");
        } finally {
            brokenApp.stop();
        }
    }

    @Test
    void streamingEmitsSseErrorEventThenDoneWhenProviderFailsMidStream() throws Exception {
        WireMockServer brokenWireMock = new WireMockServer(options().dynamicPort());
        brokenWireMock.start();
        brokenWireMock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"llama3","message":{"role":"assistant","content":"hel"},"done":false}
                        not-valid-json
                        """)));

        OllamaProvider ollama = new OllamaProvider(
                "http://localhost:" + brokenWireMock.port(), HttpClient.newHttpClient(), Duration.ofSeconds(5)
        );
        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.register(ollama);

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(GuardChain.of(MaxLengthGuard.forInput(4000)))
                .build();

        Javalin brokenApp = Aegis4jServerApp.createApp(engine, OllamaProvider.ID, null);
        brokenApp.start(0);
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + brokenApp.port() + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"model":"llama3","messages":[{"role":"user","content":"hi"}],"stream":true}
                            """))
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            assertThat(response.statusCode()).isEqualTo(200);

            List<String> dataLines;
            try (Stream<String> lines = response.body()) {
                dataLines = lines.filter(line -> line.startsWith("data: ")).map(line -> line.substring("data: ".length())).toList();
            }

            assertThat(dataLines).hasSizeGreaterThanOrEqualTo(2);
            assertThat(dataLines.get(dataLines.size() - 1)).isEqualTo("[DONE]");

            JsonNode errorEvent = mapper.readTree(dataLines.get(dataLines.size() - 2));
            assertThat(errorEvent.at("/error/code").asText()).isEqualTo("provider_error");
        } finally {
            brokenApp.stop();
            brokenWireMock.stop();
        }
    }

    @Test
    void rejectsRequestWithMissingBearerTokenWhenApiKeyConfigured() throws Exception {
        Javalin authApp = startAppWithApiKey("secret-key");
        try {
            HttpResponse<String> response = postChatCompletion(authApp, null, """
                    {"messages":[{"role":"user","content":"hello"}]}
                    """);

            assertThat(response.statusCode()).isEqualTo(401);
            JsonNode body = mapper.readTree(response.body());
            assertThat(body.at("/error/code").asText()).isEqualTo("unauthorized");
        } finally {
            authApp.stop();
        }
    }

    @Test
    void acceptsRequestWithLowercaseBearerSchemeWhenApiKeyConfigured() throws Exception {
        Javalin authApp = startAppWithApiKey("secret-key");
        try {
            HttpResponse<String> response = postChatCompletion(authApp, "bearer secret-key", """
                    {"model":"llama3","messages":[{"role":"user","content":"hello"}]}
                    """);

            assertThat(response.statusCode()).isEqualTo(200);
        } finally {
            authApp.stop();
        }
    }

    @Test
    void rejectsRequestWithTokenMissingBearerPrefixWhenApiKeyConfigured() throws Exception {
        Javalin authApp = startAppWithApiKey("secret-key");
        try {
            // Raw token with no "Bearer " scheme at all must still be rejected — guards
            // against a future regression in bearerToken() silently accepting it.
            HttpResponse<String> response = postChatCompletion(authApp, "secret-key", """
                    {"model":"llama3","messages":[{"role":"user","content":"hello"}]}
                    """);

            assertThat(response.statusCode()).isEqualTo(401);
        } finally {
            authApp.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void keepsServerOpenWhenApiKeyIsBlank(String blankApiKey) throws Exception {
        Javalin openApp = startAppWithApiKey(blankApiKey);
        try {
            HttpResponse<String> response = postChatCompletion(openApp, null, """
                    {"model":"llama3","messages":[{"role":"user","content":"hello"}]}
                    """);

            assertThat(response.statusCode()).isEqualTo(200);
        } finally {
            openApp.stop();
        }
    }

    @Test
    void rejectsRequestWithWrongBearerTokenWhenApiKeyConfigured() throws Exception {
        Javalin authApp = startAppWithApiKey("secret-key");
        try {
            HttpResponse<String> response = postChatCompletion(authApp, "Bearer wrong-key", """
                    {"messages":[{"role":"user","content":"hello"}]}
                    """);

            assertThat(response.statusCode()).isEqualTo(401);
        } finally {
            authApp.stop();
        }
    }

    @Test
    void healthEndpointStaysOpenWhenApiKeyConfigured() throws Exception {
        Javalin authApp = startAppWithApiKey("secret-key");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + authApp.port() + "/health"))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
        } finally {
            authApp.stop();
        }
    }

    private Javalin startAppWithApiKey(String apiKey) {
        Javalin authApp = Aegis4jServerApp.createApp(buildFullPipelineEngine(), OllamaProvider.ID, apiKey);
        authApp.start(0);
        return authApp;
    }

    private HttpResponse<String> postChatCompletion(String jsonBody) throws IOException, InterruptedException {
        return postChatCompletion(app, null, jsonBody);
    }

    private HttpResponse<String> postChatCompletion(Javalin targetApp, String authorizationHeader, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + targetApp.port() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (authorizationHeader != null) {
            builder.header("Authorization", authorizationHeader);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
