package dev.aegis4j.provider.anthropic;

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
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicProviderTest {

    private WireMockServer wireMock;
    private AnthropicProvider provider;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        provider = new AnthropicProvider(
                "http://localhost:" + wireMock.port(), "sk-ant-test", HttpClient.newHttpClient(), Duration.ofSeconds(5)
        );
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void completeParsesResponseAndSplitsSystemMessageOutOfMessagesArray() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("sk-ant-test"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"msg_1","model":"claude-sonnet-5","content":[{"type":"text","text":"hello there"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":3}}
                                """)));

        CompletionResponse response = provider.complete(CompletionRequest.builder()
                .model("claude-sonnet-5")
                .messages(List.of(Message.system("be nice"), Message.user("hi")))
                .build());

        assertThat(response.content()).isEqualTo("hello there");
        assertThat(response.usage().totalTokens()).isEqualTo(13);

        wireMock.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(containing("\"system\":\"be nice\""))
                .withRequestBody(containing("\"max_tokens\":4096")));
    }

    @Test
    void streamEmitsDeltasFromContentBlockDeltaEventsAndEndsOnMessageStop() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        event: message_start
                        data: {"type":"message_start"}

                        event: content_block_delta
                        data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hel"}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"lo"}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """)));

        List<CompletionChunk> chunks;
        try (var stream = provider.stream(CompletionRequest.builder()
                .model("claude-sonnet-5")
                .messages(List.of(Message.user("hi")))
                .build())) {
            chunks = stream.toList();
        }

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).deltaContent() + chunks.get(1).deltaContent()).isEqualTo("hello");
        assertThat(chunks.get(2).done()).isTrue();
    }

    @Test
    void listModelsReturnsCuratedList() {
        assertThat(provider.listModels()).extracting("id")
                .contains("claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5-20251001", "claude-fable-5-1");
    }

    @Test
    void mapsUnauthorizedStatusToProviderAuthException() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withStatus(401)
                .withBody("unauthorized")));

        assertThatThrownBy(() -> provider.complete(CompletionRequest.builder()
                .model("claude-sonnet-5")
                .messages(List.of(Message.user("hi")))
                .build()))
                .isInstanceOf(ProviderAuthException.class);
    }
}
