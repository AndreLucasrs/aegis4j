package dev.aegis4j.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.aegis4j.mcp.McpException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSseMcpTransportTest {

    private WireMockServer wireMock;
    private HttpSseMcpTransport transport;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        transport = new HttpSseMcpTransport(
                URI.create("http://localhost:" + wireMock.port() + "/mcp"),
                Map.of(),
                HttpClient.newHttpClient(),
                Duration.ofSeconds(5)
        );
    }

    @AfterEach
    void tearDown() {
        transport.close();
        wireMock.stop();
    }

    @Test
    void parsesPlainJsonResponse() {
        wireMock.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"jsonrpc":"2.0","id":1,"result":{"ok":true}}
                        """)));

        JsonNode result = transport.sendRequest("ping", Map.of(), 1);

        assertThat(result.path("ok").asBoolean()).isTrue();
    }

    @Test
    void parsesSseResponse() {
        wireMock.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"ok\":true}}\n\n")));

        JsonNode result = transport.sendRequest("ping", Map.of(), 2);

        assertThat(result.path("ok").asBoolean()).isTrue();
    }

    @Test
    void mapsJsonRpcErrorToMcpException() {
        wireMock.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"jsonrpc":"2.0","id":3,"error":{"code":-32601,"message":"Method not found"}}
                        """)));

        assertThatThrownBy(() -> transport.sendRequest("unknown", Map.of(), 3))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("Method not found");
    }
}
