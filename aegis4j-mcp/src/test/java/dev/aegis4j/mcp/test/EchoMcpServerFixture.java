package dev.aegis4j.mcp.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal, dependency-free JSON-RPC "MCP server" spawned as a real child
 * process by {@code StdioMcpTransportTest} — proves the stdio transport
 * against a genuine separate process without requiring any external binary.
 * Parses just enough of each request line (id + method) via regex to avoid
 * pulling Jackson into this tiny fixture's own classpath assumptions.
 */
public final class EchoMcpServerFixture {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");

    private EchoMcpServerFixture() {
    }

    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher idMatcher = ID_PATTERN.matcher(line);
                Matcher methodMatcher = METHOD_PATTERN.matcher(line);
                if (!idMatcher.find() || !methodMatcher.find()) {
                    continue;
                }
                String id = idMatcher.group(1);
                String result = switch (methodMatcher.group(1)) {
                    case "initialize" ->
                            "{\"serverInfo\":{\"name\":\"echo-fixture\",\"version\":\"1.0\"},\"capabilities\":{\"tools\":{}}}";
                    case "tools/list" ->
                            "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echoes input\",\"inputSchema\":{}}]}";
                    default -> "{}";
                };
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
            }
        }
    }
}
