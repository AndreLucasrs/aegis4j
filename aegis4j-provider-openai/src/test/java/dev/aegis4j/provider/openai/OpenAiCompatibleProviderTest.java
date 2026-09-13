package dev.aegis4j.provider.openai;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.ProviderAuthException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleProviderTest {

    private WireMockServer wireMock;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        provider = OpenAiCompatibleProvider.custom("test-vendor", "http://localhost:" + wireMock.port(), "sk-test-key");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void completeParsesNonStreamingResponseAndSendsBearerAuth() {
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"chatcmpl-1","model":"gpt-x","choices":[{"index":0,"message":{"role":"assistant","content":"hi there"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
                                """)));

        CompletionResponse response = provider.complete(CompletionRequest.builder()
                .model("gpt-x")
                .messages(List.of(Message.user("hi")))
                .maxTokens(100)
                .build());

        assertThat(response.content()).isEqualTo("hi there");
        assertThat(response.usage().totalTokens()).isEqualTo(7);

        wireMock.verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matching(".*\"max_tokens\":100.*")));
    }

    @Test
    void streamConcatenatesDeltasAndEndsWithDone() {
        wireMock.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"id":"1","model":"gpt-x","choices":[{"index":0,"delta":{"content":"hel"},"finish_reason":null}]}

                        data: {"id":"1","model":"gpt-x","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}

                        data: [DONE]

                        """)));

        List<CompletionChunk> chunks;
        try (var stream = provider.stream(CompletionRequest.builder()
                .model("gpt-x")
                .messages(List.of(Message.user("hi")))
                .build())) {
            chunks = stream.toList();
        }

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).deltaContent() + chunks.get(1).deltaContent()).isEqualTo("hello");
        assertThat(chunks.get(2).done()).isTrue();
    }

    @Test
    void listModelsParsesDataArray() {
        wireMock.stubFor(get(urlEqualTo("/models")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"data":[{"id":"gpt-x"},{"id":"gpt-y"}]}
                        """)));

        var models = provider.listModels();

        assertThat(models).extracting("id").containsExactly("gpt-x", "gpt-y");
    }

    @Test
    void mapsUnauthorizedStatusToProviderAuthException() {
        wireMock.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
                .withStatus(401)
                .withBody("unauthorized")));

        assertThatThrownBy(() -> provider.complete(CompletionRequest.builder()
                .model("gpt-x")
                .messages(List.of(Message.user("hi")))
                .build()))
                .isInstanceOf(ProviderAuthException.class);
    }
}
