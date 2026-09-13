package dev.aegis4j.provider.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.FinishReason;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.ModelInfo;
import dev.aegis4j.api.provider.Provider;
import dev.aegis4j.api.provider.ProviderException;
import dev.aegis4j.api.provider.ProviderTimeoutException;
import dev.aegis4j.api.provider.Usage;
import dev.aegis4j.provider.http.ProviderHttpErrors;
import dev.aegis4j.provider.http.SseLineParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A single {@link Provider} implementation that talks to any backend
 * exposing an OpenAI-compatible Chat Completions API — OpenAI itself,
 * DeepSeek, and many others that mirror the same wire format. One instance
 * per vendor, distinguished by {@code id}, {@code baseUrl} and
 * {@code apiKey}; unlike {@code OllamaProvider} this is not
 * {@code ServiceLoader}-discoverable (there is no single sensible default
 * instance for "the" OpenAI-compatible provider) — register explicitly via
 * {@code Aegis4jEngine.Builder.provider(...)}.
 *
 * <p>{@code baseUrl} must include everything up to (but not including)
 * {@code /chat/completions} — e.g. {@code https://api.openai.com/v1} for
 * OpenAI, {@code https://api.deepseek.com} for DeepSeek. Vendors differ on
 * whether they use a {@code /v1} prefix; this class never assumes one.
 */
public final class OpenAiCompatibleProvider implements Provider {

    private final String id;
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public OpenAiCompatibleProvider(String id, String baseUrl, String apiKey, HttpClient httpClient, Duration timeout) {
        this.id = id;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.timeout = timeout;
    }

    public static OpenAiCompatibleProvider openAi() {
        return custom("openai", "https://api.openai.com/v1", System.getenv("AEGIS4J_OPENAI_API_KEY"));
    }

    public static OpenAiCompatibleProvider deepSeek() {
        return custom("deepseek", "https://api.deepseek.com", System.getenv("AEGIS4J_DEEPSEEK_API_KEY"));
    }

    /** For any other OpenAI-compatible backend (Kimi/Moonshot, Groq, Together AI, OpenRouter, a local vLLM/LM Studio server, ...). */
    public static OpenAiCompatibleProvider custom(String id, String baseUrl, String apiKey) {
        return new OpenAiCompatibleProvider(id, baseUrl, apiKey, HttpClient.newHttpClient(), Duration.ofSeconds(60));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        HttpResponse<String> response = send(buildRequest(request, false), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw ProviderHttpErrors.map(id, response.statusCode(), response.body());
        }

        OpenAiChatResponse chatResponse = parse(response.body(), OpenAiChatResponse.class);
        OpenAiChoice choice = chatResponse.choices().get(0);
        Usage usage = chatResponse.usage() == null
                ? Usage.UNKNOWN
                : new Usage(chatResponse.usage().promptTokens(), chatResponse.usage().completionTokens(), chatResponse.usage().totalTokens());

        return new CompletionResponse(
                chatResponse.id(), chatResponse.model(), choice.message().content(),
                mapFinishReason(choice.finishReason()), usage, List.of()
        );
    }

    @Override
    public Stream<CompletionChunk> stream(CompletionRequest request) {
        HttpResponse<Stream<String>> response = send(buildRequest(request, true), HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            String body = response.body().reduce("", (a, b) -> a + b);
            throw ProviderHttpErrors.map(id, response.statusCode(), body);
        }

        return SseLineParser.dataPayloads(response.body())
                .map(payload -> "[DONE]".equals(payload) ? CompletionChunk.finished() : toChunk(payload));
    }

    @Override
    public List<ModelInfo> listModels() {
        HttpRequest httpRequest = authorizedBuilder(URI.create(baseUrl + "/models")).GET().build();
        HttpResponse<String> response = send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw ProviderHttpErrors.map(id, response.statusCode(), response.body());
        }

        OpenAiModelsResponse models = parse(response.body(), OpenAiModelsResponse.class);
        return models.data().stream().map(model -> new ModelInfo(model.id(), model.id(), null)).toList();
    }

    private CompletionChunk toChunk(String payload) {
        OpenAiStreamChunk chunk = parse(payload, OpenAiStreamChunk.class);
        OpenAiStreamChoice choice = chunk.choices().get(0);
        String delta = choice.delta() == null || choice.delta().content() == null ? "" : choice.delta().content();
        return CompletionChunk.ofDelta(delta);
    }

    private FinishReason mapFinishReason(String raw) {
        if (raw == null) {
            return FinishReason.STOP;
        }
        return switch (raw) {
            case "length" -> FinishReason.LENGTH;
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.STOP;
        };
    }

    private HttpRequest buildRequest(CompletionRequest request, boolean stream) {
        List<OpenAiMessage> messages = request.messages().stream()
                .map(this::toOpenAiMessage)
                .toList();
        OpenAiChatRequest body = new OpenAiChatRequest(request.model(), messages, stream, request.temperature(), request.maxTokens());

        return authorizedBuilder(URI.create(baseUrl + "/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build();
    }

    private OpenAiMessage toOpenAiMessage(Message message) {
        return new OpenAiMessage(message.role().name().toLowerCase(Locale.ROOT), message.content());
    }

    private HttpRequest.Builder authorizedBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (IOException e) {
            throw new ProviderTimeoutException(id, "Failed to call " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderTimeoutException(id, "Interrupted while calling " + baseUrl, e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize request for " + id, e);
        }
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            throw new ProviderException(id, 0, "Failed to parse response from " + id + ": " + json, e);
        }
    }
}
