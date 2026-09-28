package dev.aegis4j.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.aegis4j.api.rag.Retriever;
import dev.aegis4j.core.engine.Aegis4jEngine;
import dev.aegis4j.core.guard.GuardChain;
import dev.aegis4j.core.provider.ProviderRegistry;
import dev.aegis4j.core.routing.ModelRouter;
import dev.aegis4j.core.skill.SkillRegistry;
import dev.aegis4j.guardrails.builtin.MaxLengthGuard;
import dev.aegis4j.guardrails.builtin.RegexPiiGuard;
import dev.aegis4j.mcp.McpClient;
import dev.aegis4j.mcp.McpTransport;
import dev.aegis4j.mcp.transport.HttpSseMcpTransport;
import dev.aegis4j.mcp.transport.StdioMcpTransport;
import dev.aegis4j.provider.ollama.OllamaProvider;
import dev.aegis4j.provider.openai.OpenAiCompatibleProvider;
import dev.aegis4j.rag.mcp.McpToolArgumentMapper;
import dev.aegis4j.rag.mcp.McpToolRetriever;
import dev.aegis4j.routing.yaml.YamlRoutingRuleLoader;
import dev.aegis4j.server.http.BearerAuthHandler;
import dev.aegis4j.server.http.ChatCompletionsHandler;
import dev.aegis4j.skills.markdown.MarkdownSkillLoader;
import io.javalin.Javalin;
import io.javalin.compression.CompressionStrategy;
import io.javalin.compression.Gzip;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class Aegis4jServerApp {

    private Aegis4jServerApp() {
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("AEGIS4J_PORT", "8686"));
        String providerId = System.getenv().getOrDefault("AEGIS4J_PROVIDER_ID", OllamaProvider.ID);
        int maxInputChars = Integer.parseInt(System.getenv().getOrDefault("AEGIS4J_MAX_INPUT_CHARS", "4000"));
        String skillsDir = System.getenv("AEGIS4J_SKILLS_DIR");
        String serverApiKey = System.getenv("AEGIS4J_SERVER_API_KEY");

        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.discover(Thread.currentThread().getContextClassLoader());
        registerOpenAiCompatibleProviderIfConfigured(providerRegistry);

        SkillRegistry skillRegistry = SkillRegistry.inMemory();
        if (skillsDir != null && !skillsDir.isBlank()) {
            new MarkdownSkillLoader().loadDirectory(Path.of(skillsDir)).forEach(skillRegistry::register);
        }

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(GuardChain.of(MaxLengthGuard.forInput(maxInputChars), RegexPiiGuard.allPatterns()))
                .skillRegistry(skillRegistry)
                .retriever(buildRetriever())
                .modelRouter(buildModelRouter())
                .build();

        createApp(engine, providerId, serverApiKey).start(port);
    }

    /**
     * v0.2: only the MCP-backed retriever is turnkey via env vars.
     * {@code AEGIS4J_RETRIEVER=pgvector} requires a caller-supplied
     * {@link dev.aegis4j.api.rag.Embedder}, which doesn't exist yet as a
     * concrete implementation (no {@code OllamaEmbedder} until v0.3) — wire
     * {@link dev.aegis4j.rag.pgvector.PgVectorRetriever} directly via the
     * embedded-library API instead of through this server for now.
     */
    private static Retriever buildRetriever() {
        String retrieverKind = System.getenv("AEGIS4J_RETRIEVER");
        if (retrieverKind == null || retrieverKind.isBlank()) {
            return null;
        }
        if ("pgvector".equals(retrieverKind)) {
            throw new IllegalStateException(
                    "AEGIS4J_RETRIEVER=pgvector requires an Embedder, which this server does not wire yet "
                            + "(no concrete Embedder implementation until v0.3). "
                            + "Build PgVectorRetriever directly via the embedded-library API instead."
            );
        }
        if (!"mcp".equals(retrieverKind)) {
            throw new IllegalStateException("Unknown AEGIS4J_RETRIEVER value: " + retrieverKind + " (expected 'pgvector' or 'mcp')");
        }

        String transportKind = System.getenv("AEGIS4J_MCP_RETRIEVER_TRANSPORT");
        McpTransport transport = "stdio".equals(transportKind)
                ? new StdioMcpTransport(
                        splitCommand(requireEnv("AEGIS4J_MCP_RETRIEVER_COMMAND")), Map.of(), Duration.ofSeconds(30))
                : new HttpSseMcpTransport(
                        URI.create(requireEnv("AEGIS4J_MCP_RETRIEVER_URL")), Map.of(), HttpClient.newHttpClient(), Duration.ofSeconds(30));

        McpClient mcpClient = new McpClient(transport, "aegis4j-server", "0.2.0");
        mcpClient.initialize();
        String toolName = System.getenv().getOrDefault("AEGIS4J_MCP_RETRIEVER_TOOL", "search");
        return new McpToolRetriever(mcpClient, toolName, McpToolArgumentMapper.defaultMapper());
    }

    /**
     * Anthropic registers itself automatically via {@code ProviderRegistry.discover}
     * ({@code META-INF/services}) once {@code AEGIS4J_ANTHROPIC_API_KEY} is set and
     * {@code AEGIS4J_PROVIDER_ID=anthropic} is selected — no wiring needed here.
     * {@code OpenAiCompatibleProvider} is NOT ServiceLoader-discoverable (it needs
     * an explicit id/baseUrl/apiKey per vendor), so it's wired here if configured.
     */
    private static void registerOpenAiCompatibleProviderIfConfigured(ProviderRegistry providerRegistry) {
        String id = System.getenv("AEGIS4J_OPENAI_COMPATIBLE_ID");
        if (id == null || id.isBlank()) {
            return;
        }
        String baseUrl = requireEnv("AEGIS4J_OPENAI_COMPATIBLE_BASE_URL");
        String apiKey = System.getenv("AEGIS4J_OPENAI_COMPATIBLE_API_KEY");
        providerRegistry.register(OpenAiCompatibleProvider.custom(id, baseUrl, apiKey));
    }

    private static ModelRouter buildModelRouter() {
        String routingConfig = System.getenv("AEGIS4J_ROUTING_CONFIG");
        if (routingConfig == null || routingConfig.isBlank()) {
            return null;
        }
        Path routingPath = Path.of(routingConfig);
        YamlRoutingRuleLoader loader = new YamlRoutingRuleLoader();
        return new ModelRouter(loader.loadFile(routingPath), loader.loadDefaultTarget(routingPath));
    }

    private static List<String> splitCommand(String command) {
        return Arrays.asList(command.trim().split("\\s+"));
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    /**
     * @param serverApiKey expected bearer token for non-health endpoints, or {@code null}/blank
     *                      to keep the server open (default, backward-compatible behavior).
     */
    public static Javalin createApp(Aegis4jEngine engine, String providerId, String serverApiKey) {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        Javalin app = Javalin.create(config -> config.http.customCompression(sseSafeCompressionStrategy()));
        app.get("/health", ctx -> ctx.result("ok"));
        if (serverApiKey != null && !serverApiKey.isBlank()) {
            app.before(new BearerAuthHandler(serverApiKey, mapper));
        }
        app.post("/v1/chat/completions", new ChatCompletionsHandler(engine, providerId, mapper));
        return app;
    }

    /**
     * Javalin's default {@code CompressionStrategy.GZIP} doesn't exclude
     * {@code text/event-stream}: a large enough first SSE write would get
     * transparently gzip-encoded, which is valid HTTP but defeats the point
     * of streaming (compression buffers output instead of flushing it chunk
     * by chunk) and typical SSE clients don't expect it. Excluding it here
     * keeps gzip for every other response this server sends.
     */
    private static CompressionStrategy sseSafeCompressionStrategy() {
        CompressionStrategy strategy = new CompressionStrategy(null, new Gzip(6));
        List<String> excludedMimeTypes = new ArrayList<>(strategy.getExcludedMimeTypes());
        excludedMimeTypes.add("text/event-stream");
        strategy.setExcludedMimeTypes(excludedMimeTypes);
        return strategy;
    }
}
