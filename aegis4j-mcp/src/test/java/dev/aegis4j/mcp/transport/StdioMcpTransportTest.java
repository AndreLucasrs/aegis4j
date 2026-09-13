package dev.aegis4j.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StdioMcpTransportTest {

    private StdioMcpTransport transport;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
    }

    @Test
    void sendRequestReceivesCorrelatedResponseFromRealChildProcess() {
        transport = new StdioMcpTransport(echoFixtureCommand(), Map.of(), Duration.ofSeconds(10));

        JsonNode result = transport.sendRequest("initialize", Map.of(), 1);

        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("echo-fixture");
    }

    @Test
    void toolsListAlsoWorks() {
        transport = new StdioMcpTransport(echoFixtureCommand(), Map.of(), Duration.ofSeconds(10));

        JsonNode result = transport.sendRequest("tools/list", Map.of(), 1);

        assertThat(result.path("tools").path(0).path("name").asText()).isEqualTo("echo");
    }

    private List<String> echoFixtureCommand() {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        return List.of(javaBin, "-cp", System.getProperty("java.class.path"), "dev.aegis4j.mcp.test.EchoMcpServerFixture");
    }
}
