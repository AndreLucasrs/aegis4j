package dev.aegis4j.provider.ollama;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.ProviderAuthException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaProviderTest {

    private WireMockServer wireMock;
    private OllamaProvider provider;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        provider = new OllamaProvider("http://localhost:" + wireMock.port(), HttpClient.newHttpClient(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void completeParsesNonStreamingResponse() {
        wireMock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"llama3","message":{"role":"assistant","content":"hello there"},"done":true}
                        """)));

        CompletionResponse response = provider.complete(CompletionRequest.builder()
                .model("llama3")
                .messages(List.of(Message.user("hi")))
                .build());

        assertThat(response.content()).isEqualTo("hello there");
        assertThat(response.model()).isEqualTo("llama3");
    }

    @Test
    void completeIgnoresThinkingFieldFromReasoningModels() {
        // "Thinking" models (e.g. deepseek-r1) add a "thinking" field carrying
        // chain-of-thought alongside "content" — must not break parsing.
        wireMock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"deepseek-r1:14b","message":{"role":"assistant","thinking":"reasoning...","content":"the answer"},"done":true}
                        """)));

        CompletionResponse response = provider.complete(CompletionRequest.builder()
                .model("deepseek-r1:14b")
                .messages(List.of(Message.user("hi")))
                .build());

        assertThat(response.content()).isEqualTo("the answer");
    }

    @Test
    void streamConcatenatesDeltasAndEndsWithDone() {
        wireMock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"llama3","message":{"role":"assistant","content":"hel"},"done":false}
                        {"model":"llama3","message":{"role":"assistant","content":"lo"},"done":false}
                        {"model":"llama3","message":{"role":"assistant","content":""},"done":true}
                        """)));

        List<CompletionChunk> chunks;
        try (var stream = provider.stream(CompletionRequest.builder()
                .model("llama3")
                .messages(List.of(Message.user("hi")))
                .build())) {
            chunks = stream.toList();
        }

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).deltaContent() + chunks.get(1).deltaContent()).isEqualTo("hello");
        assertThat(chunks.get(2).done()).isTrue();
    }

    @Test
    void listModelsParsesTagsResponse() {
        wireMock.stubFor(get(urlEqualTo("/api/tags")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"models":[{"name":"llama3:latest"},{"name":"qwen2.5-coder:7b"}]}
                        """)));

        var models = provider.listModels();

        assertThat(models).extracting("id").containsExactly("llama3:latest", "qwen2.5-coder:7b");
    }

    @Test
    void mapsUnauthorizedStatusToProviderAuthException() {
        wireMock.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse()
                .withStatus(401)
                .withBody("unauthorized")));

        assertThatThrownBy(() -> provider.complete(CompletionRequest.builder()
                .model("llama3")
                .messages(List.of(Message.user("hi")))
                .build()))
                .isInstanceOf(ProviderAuthException.class);
    }
}
