package dev.aegis4j.provider.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.FinishReason;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.ModelInfo;
import dev.aegis4j.api.provider.Provider;
import dev.aegis4j.api.provider.Usage;
import dev.aegis4j.provider.http.NdjsonLineParser;
import dev.aegis4j.provider.http.ProviderHttpErrors;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** {@link Provider} for a local (or remote) Ollama instance's {@code /api/chat} and {@code /api/tags} endpoints. */
public final class OllamaProvider implements Provider {

    public static final String ID = "ollama";
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public OllamaProvider(String baseUrl, HttpClient httpClient, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = httpClient;
        this.timeout = timeout;
    }

    /** No-arg constructor required by {@link java.util.ServiceLoader} for {@code ProviderRegistry.discover}; reads config from env vars. */
    public OllamaProvider() {
        this(System.getenv().getOrDefault("AEGIS4J_OLLAMA_BASE_URL", DEFAULT_BASE_URL), HttpClient.newHttpClient(), Duration.ofSeconds(60));
    }

    public static OllamaProvider create() {
        return new OllamaProvider();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        HttpRequest httpRequest = buildRequest(request, false);
        HttpResponse<String> response = send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw ProviderHttpErrors.map(ID, response.statusCode(), response.body());
        }

        OllamaChatResponseChunk chunk = parse(response.body(), OllamaChatResponseChunk.class);
        String content = chunk.message() == null ? "" : chunk.message().content();
        return new CompletionResponse(
                "ollama-" + UUID.randomUUID(), chunk.model(), content, FinishReason.STOP, Usage.UNKNOWN, List.of()
        );
    }

    @Override
    public Stream<CompletionChunk> stream(CompletionRequest request) {
        HttpRequest httpRequest = buildRequest(request, true);
        HttpResponse<Stream<String>> response = send(httpRequest, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            String body = response.body().reduce("", (a, b) -> a + b);
            throw ProviderHttpErrors.map(ID, response.statusCode(), body);
        }

        return NdjsonLineParser.nonBlankLines(response.body()).map(line -> {
            OllamaChatResponseChunk chunk = parse(line, OllamaChatResponseChunk.class);
            if (chunk.done()) {
                return CompletionChunk.finished();
            }
            String delta = chunk.message() == null ? "" : chunk.message().content();
            return CompletionChunk.ofDelta(delta);
        });
    }

    @Override
    public List<ModelInfo> listModels() {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> response = send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw ProviderHttpErrors.map(ID, response.statusCode(), response.body());
        }

        OllamaTagsResponse tags = parse(response.body(), OllamaTagsResponse.class);
        return tags.models().stream()
                .map(model -> new ModelInfo(model.name(), model.name(), null))
                .toList();
    }

    private HttpRequest buildRequest(CompletionRequest request, boolean stream) {
        List<OllamaMessage> messages = request.messages().stream()
                .map(this::toOllamaMessage)
                .toList();

        Map<String, Object> options = new HashMap<>();
        if (request.temperature() != null) {
            options.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            options.put("num_predict", request.maxTokens());
        }

        OllamaChatRequest body = new OllamaChatRequest(request.model(), messages, stream, options.isEmpty() ? null : options);
        String json = writeJson(body);

        return HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private OllamaMessage toOllamaMessage(Message message) {
        return new OllamaMessage(message.role().name().toLowerCase(), message.content());
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (IOException e) {
            throw new dev.aegis4j.api.provider.ProviderTimeoutException(ID, "Failed to call Ollama at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new dev.aegis4j.api.provider.ProviderTimeoutException(ID, "Interrupted while calling Ollama", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Ollama request", e);
        }
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            throw new dev.aegis4j.api.provider.ProviderException(ID, 0, "Failed to parse Ollama response: " + json, e);
        }
    }
}
