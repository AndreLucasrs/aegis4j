package dev.aegis4j.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.FinishReason;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.ModelInfo;
import dev.aegis4j.api.provider.Provider;
import dev.aegis4j.api.provider.ProviderException;
import dev.aegis4j.api.provider.ProviderTimeoutException;
import dev.aegis4j.api.provider.Role;
import dev.aegis4j.api.provider.Usage;
import dev.aegis4j.provider.http.ProviderHttpErrors;
import dev.aegis4j.provider.http.SseLineParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@link Provider} for Anthropic's Messages API — a genuinely different wire
 * format from the OpenAI-compatible shape ({@link dev.aegis4j.provider.openai.OpenAiCompatibleProvider}):
 * {@code max_tokens} is required with no default, system prompt is a
 * top-level field rather than a message with role {@code system}, and
 * streaming is a sequence of named SSE events rather than one delta shape.
 */
public final class AnthropicProvider implements Provider {

    public static final String ID = "anthropic";
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicProvider(String baseUrl, String apiKey, HttpClient httpClient, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.timeout = timeout;
    }

    /** No-arg constructor required by {@link java.util.ServiceLoader} for {@code ProviderRegistry.discover}; reads config from env vars. */
    public AnthropicProvider() {
        this(DEFAULT_BASE_URL, System.getenv("AEGIS4J_ANTHROPIC_API_KEY"), HttpClient.newHttpClient(), Duration.ofSeconds(60));
    }

    public static AnthropicProvider create() {
        return new AnthropicProvider();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        HttpResponse<String> response = send(buildHttpRequest(request, false), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw ProviderHttpErrors.map(ID, response.statusCode(), response.body());
        }

        JsonNode node = parse(response.body());
        Usage usage = extractUsage(node);

        return new CompletionResponse(
                node.path("id").asText(""),
                node.path("model").asText(request.model()),
                extractText(node.path("content")),
                mapStopReason(node.path("stop_reason").asText(null)),
                usage,
                List.of()
        );
    }

    @Override
    public Stream<CompletionChunk> stream(CompletionRequest request) {
        HttpResponse<Stream<String>> response = send(buildHttpRequest(request, true), HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            String body = response.body().reduce("", (a, b) -> a + b);
            throw ProviderHttpErrors.map(ID, response.statusCode(), body);
        }

        return SseLineParser.dataPayloads(response.body())
                .map(this::parse)
                .map(this::toChunk)
                .filter(chunk -> chunk != null);
    }

    /**
     * Anthropic has no public models-list endpoint (unlike Ollama/OpenAI); this
     * is a small manually maintained list, overridable by callers who need a
     * different/newer set — update it as new models ship.
     */
    @Override
    public List<ModelInfo> listModels() {
        return List.of(
                new ModelInfo("claude-opus-5", "Claude Opus 5", null),
                new ModelInfo("claude-sonnet-5", "Claude Sonnet 5", null),
                new ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5", null),
                new ModelInfo("claude-fable-5-1", "Claude Fable 5.1", null)
        );
    }

    private CompletionChunk toChunk(JsonNode node) {
        String type = node.path("type").asText("");
        if ("content_block_delta".equals(type)) {
            return CompletionChunk.ofDelta(node.path("delta").path("text").asText(""));
        }
        if ("message_stop".equals(type)) {
            return CompletionChunk.finished();
        }
        return null;
    }

    private HttpRequest buildHttpRequest(CompletionRequest request, boolean stream) {
        List<Map<String, String>> messages = new ArrayList<>();
        StringBuilder systemPrompt = new StringBuilder();
        for (Message message : request.messages()) {
            if (message.role() == Role.SYSTEM) {
                if (systemPrompt.length() > 0) {
                    systemPrompt.append("\n\n");
                }
                systemPrompt.append(message.content());
            } else {
                messages.add(Map.of("role", message.role().name().toLowerCase(Locale.ROOT), "content", message.content()));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : DEFAULT_MAX_TOKENS);
        body.put("messages", messages);
        if (!systemPrompt.isEmpty()) {
            body.put("system", systemPrompt.toString());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        body.put("stream", stream);

        return HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey == null ? "" : apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build();
    }

    private String extractText(JsonNode contentArray) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : contentArray) {
            if ("text".equals(block.path("type").asText(""))) {
                text.append(block.path("text").asText(""));
            }
        }
        return text.toString();
    }

    private Usage extractUsage(JsonNode node) {
        JsonNode usageNode = node.path("usage");
        if (usageNode.isMissingNode()) {
            return Usage.UNKNOWN;
        }
        int inputTokens = usageNode.path("input_tokens").asInt(0);
        int outputTokens = usageNode.path("output_tokens").asInt(0);
        return new Usage(inputTokens, outputTokens, inputTokens + outputTokens);
    }

    private FinishReason mapStopReason(String raw) {
        if (raw == null) {
            return FinishReason.STOP;
        }
        return switch (raw) {
            case "max_tokens" -> FinishReason.LENGTH;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            default -> FinishReason.STOP;
        };
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (IOException e) {
            throw new ProviderTimeoutException(ID, "Failed to call Anthropic at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderTimeoutException(ID, "Interrupted while calling Anthropic", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Anthropic request", e);
        }
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new ProviderException(ID, 0, "Failed to parse Anthropic response: " + json, e);
        }
    }
}
