package dev.aegis4j.mcp;

import dev.aegis4j.api.mcp.McpContentBlock;
import dev.aegis4j.api.mcp.McpToolDescriptor;
import dev.aegis4j.api.mcp.McpToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientTest {

    private final FakeMcpTransport transport = new FakeMcpTransport();
    private final McpClient client = new McpClient(transport, "test-client", "1.0.0");

    @Test
    void initializeSendsHandshakeAndParsesServerInfo() {
        transport.respondTo("initialize", """
                {"serverInfo":{"name":"test-server","version":"9.9"},"capabilities":{"tools":{},"resources":{}}}
                """);

        McpServerInfo info = client.initialize();

        assertThat(info.name()).isEqualTo("test-server");
        assertThat(info.version()).isEqualTo("9.9");
        assertThat(info.capabilities().tools()).isTrue();
        assertThat(info.capabilities().resources()).isTrue();
        assertThat(info.capabilities().prompts()).isFalse();

        List<FakeMcpTransport.SentRequest> sent = transport.sentRequests();
        assertThat(sent).anyMatch(r -> r.method().equals("initialize"));
        assertThat(sent).anyMatch(r -> r.method().equals("notifications/initialized"));
    }

    @Test
    void listToolsParsesDescriptors() {
        transport.respondTo("tools/list", """
                {"tools":[{"name":"search","description":"Search docs","inputSchema":{"type":"object"}}]}
                """);

        List<McpToolDescriptor> tools = client.listTools();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("search");
        assertThat(tools.get(0).description()).isEqualTo("Search docs");
    }

    @Test
    void callToolMapsTextContentBlocks() {
        transport.respondTo("tools/call", """
                {"content":[{"type":"text","text":"result chunk"}],"isError":false}
                """);

        McpToolResult result = client.callTool("search", Map.of("query", "weather"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(((McpContentBlock.Text) result.content().get(0)).text()).isEqualTo("result chunk");
    }

    @Test
    void listPromptsThrowsUnsupported() {
        assertThatThrownBy(client::listPrompts).isInstanceOf(UnsupportedOperationException.class);
    }
}
