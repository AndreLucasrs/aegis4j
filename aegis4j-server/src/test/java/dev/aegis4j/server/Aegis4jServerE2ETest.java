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

    private WireMockServer wireMock;
    private Javalin app;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
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

        OllamaProvider ollama = new OllamaProvider(
                "http://localhost:" + wireMock.port(), HttpClient.newHttpClient(), Duration.ofSeconds(5)
        );
        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.register(ollama);

        SkillRegistry skillRegistry = SkillRegistry.inMemory();
        new MarkdownSkillLoader().loadDirectory(tempDir).forEach(skillRegistry::register);

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(GuardChain.of(MaxLengthGuard.forInput(4000), RegexPiiGuard.allPatterns()))
                .skillRegistry(skillRegistry)
                .build();

        app = Aegis4jServerApp.createApp(engine, OllamaProvider.ID);
        app.start(0);
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

        Javalin routedApp = Aegis4jServerApp.createApp(routedEngine, OllamaProvider.ID);
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

    private HttpResponse<String> postChatCompletion(String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
