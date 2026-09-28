package dev.aegis4j.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.FinishReason;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.ProviderAuthException;
import dev.aegis4j.api.provider.ProviderException;
import dev.aegis4j.api.provider.ProviderRateLimitException;
import dev.aegis4j.api.provider.ProviderTimeoutException;
import dev.aegis4j.api.provider.Role;
import dev.aegis4j.core.engine.Aegis4jEngine;
import dev.aegis4j.core.engine.ChatRequest;
import dev.aegis4j.core.engine.StreamedCompletion;
import dev.aegis4j.core.engine.ToolCallLimitExceededException;
import dev.aegis4j.core.guard.GuardBlockedException;
import dev.aegis4j.server.dto.ChatChoiceDto;
import dev.aegis4j.server.dto.ChatChunkChoiceDto;
import dev.aegis4j.server.dto.ChatCompletionChunkDto;
import dev.aegis4j.server.dto.ChatCompletionRequestDto;
import dev.aegis4j.server.dto.ChatCompletionResponseDto;
import dev.aegis4j.server.dto.ChatDeltaDto;
import dev.aegis4j.server.dto.ChatMessageDto;
import dev.aegis4j.server.dto.ErrorDetailDto;
import dev.aegis4j.server.dto.ErrorResponseDto;
import dev.aegis4j.server.dto.UsageDto;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * {@code POST /v1/chat/completions}, OpenAI-compatible request/response shape.
 * Non-streaming by default; set {@code "stream": true} on the request to get
 * a {@code text/event-stream} response of {@code chat.completion.chunk}
 * events instead, terminated by a {@code data: [DONE]} event. Streamed
 * responses do NOT go through output guards — see
 * {@link Aegis4jEngine#chatStream} javadoc — so a streamed answer may
 * contain content a non-streaming call to the same engine would have
 * redacted or blocked.
 *
 * <p><b>Known capacity limitation:</b> streaming writes to the response
 * {@code OutputStream} synchronously on the request-handling thread for the
 * whole lifetime of the stream (Javalin/Jetty's default is a blocking I/O
 * thread pool). A large number of concurrent streaming connections can
 * exhaust that pool. Fixing this properly means moving to async I/O
 * ({@code ctx.future()} plus a dedicated executor), which is a bigger change
 * than this handler takes on today.
 */
public final class ChatCompletionsHandler implements Handler {

    private final Aegis4jEngine engine;
    private final String providerId;
    private final ObjectMapper mapper;

    public ChatCompletionsHandler(Aegis4jEngine engine, String providerId, ObjectMapper mapper) {
        this.engine = engine;
        this.providerId = providerId;
        this.mapper = mapper;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        ChatCompletionRequestDto requestDto = mapper.readValue(ctx.body(), ChatCompletionRequestDto.class);

        if (requestDto.messages() == null || requestDto.messages().isEmpty()) {
            writeJsonError(ctx, 400, "invalid_request", "messages must not be empty");
            return;
        }

        List<ChatMessageDto> messages = requestDto.messages();
        ChatMessageDto lastMessage = messages.get(messages.size() - 1);
        List<Message> history = messages.subList(0, messages.size() - 1).stream()
                .map(dto -> new Message(Role.valueOf(dto.role().toUpperCase(Locale.ROOT)), dto.content()))
                .toList();

        ChatRequest chatRequest = ChatRequest.builder()
                .providerId(providerId)
                .model(requestDto.model())
                .history(history)
                .userInput(lastMessage.content())
                .build();

        if (Boolean.TRUE.equals(requestDto.stream())) {
            handleStreaming(ctx, chatRequest);
            return;
        }

        try {
            CompletionResponse response = engine.chat(chatRequest);
            ctx.contentType("application/json");
            ctx.result(mapper.writeValueAsString(toDto(response)));
        } catch (GuardBlockedException | IllegalStateException | NoSuchElementException
                | ProviderException | ToolCallLimitExceededException e) {
            // Same exception surface Aegis4jEngine#chat can throw (guard block,
            // routing misconfigured, unknown provider, the provider itself
            // failing, or the tool-calling loop giving up) mapped through the
            // same {@link #mapError} lookup handleStreaming() uses for its
            // pre-stream errors, so the two paths can't drift apart on status/code.
            ErrorMapping mapping = mapError(e);
            writeJsonError(ctx, mapping.httpStatus(), mapping.code(), e.getMessage());
        }
    }

    /**
     * {@link Aegis4jEngine#chatStream}'s javadoc guarantees that input
     * guards, retrieval, routing and opening the provider's streaming
     * connection all run synchronously before it returns — so
     * {@link GuardBlockedException}, {@link IllegalStateException} (routing
     * misconfigured), {@link NoSuchElementException} (unregistered provider
     * id) and {@link ProviderException} (the provider rejected the request,
     * timed out, etc. while opening the connection) are all still reported
     * here as a normal HTTP error, exactly like the non-streaming path — no
     * SSE bytes have been written yet at this point. (See {@link #handle}:
     * it catches this same set, plus {@link ToolCallLimitExceededException}
     * which only {@link Aegis4jEngine#chat} can throw, through the same
     * {@link #mapError} lookup.)
     *
     * <p>Once the first byte is written the response is committed to 200
     * {@code text/event-stream} and the HTTP status can no longer change;
     * any exception raised while pulling further chunks (the same types
     * above, anything else the provider's lazy stream throws, or a
     * {@code JsonProcessingException} serializing a chunk to JSON) is
     * instead reported as a best-effort {@code data:} event using the same
     * {@code {"error": {"code": ..., "message": ...}}} shape, followed by
     * {@code data: [DONE]} — that terminator is always written, even when
     * the failure happens mid-loop, so a client is never left waiting on a
     * stream that silently died.
     */
    private void handleStreaming(Context ctx, ChatRequest chatRequest) throws IOException {
        StreamedCompletion streamed;
        try {
            streamed = engine.chatStream(chatRequest);
        } catch (GuardBlockedException | IllegalStateException | NoSuchElementException | ProviderException e) {
            ErrorMapping mapping = mapError(e);
            writeJsonError(ctx, mapping.httpStatus(), mapping.code(), e.getMessage());
            return;
        }

        ctx.status(200);
        ctx.contentType("text/event-stream");
        ctx.header("Cache-Control", "no-cache");
        ctx.header("Connection", "keep-alive");
        ctx.header("X-Accel-Buffering", "no");

        String completionId = "chatcmpl-" + streamed.id();
        // The OpenAI wire format uses the same "created" timestamp on every
        // chunk of one stream, so this is resolved once, not per chunk.
        long createdAt = Instant.now().getEpochSecond();
        SseWriter sseWriter = new SseWriter(ctx.outputStream());
        boolean roleSent = false;
        try (Stream<CompletionChunk> chunks = streamed.chunks()) {
            for (Iterator<CompletionChunk> it = chunks.iterator(); it.hasNext(); ) {
                CompletionChunk chunk = it.next();
                ChatCompletionChunkDto dto = toChunkDto(chunk, completionId, streamed.model(), createdAt, !roleSent);
                sseWriter.writeJson(mapper.writeValueAsBytes(dto));
                roleSent = true;
            }
        } catch (Exception e) {
            // Exception, not RuntimeException: mapper.writeValueAsBytes above can
            // throw the checked JsonProcessingException, which a narrower catch
            // would let escape this method entirely, skipping writeDone() below
            // and leaving an SSE client waiting forever for a [DONE] that never
            // comes.
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            ErrorResponseDto errorDto = new ErrorResponseDto(new ErrorDetailDto(mapError(e).code(), message));
            sseWriter.writeJson(mapper.writeValueAsBytes(errorDto));
        }
        sseWriter.writeDone();
    }

    private ChatCompletionChunkDto toChunkDto(CompletionChunk chunk, String id, String model, long createdAt, boolean includeRole) {
        ChatDeltaDto delta = new ChatDeltaDto(includeRole ? "assistant" : null, chunk.done() ? null : chunk.deltaContent());
        String finishReason = chunk.done() ? finishReasonName(chunk.finishReason()) : null;
        ChatChunkChoiceDto choice = new ChatChunkChoiceDto(0, delta, finishReason);
        return new ChatCompletionChunkDto(id, "chat.completion.chunk", createdAt, model, List.of(choice));
    }

    /**
     * {@code null} means the provider's stream didn't carry a reason for this
     * chunk (see {@link CompletionChunk} javadoc) — the stream still ended
     * normally, so this falls back to {@code "stop"} rather than inventing
     * something more specific or leaving the field out.
     */
    private String finishReasonName(FinishReason finishReason) {
        return (finishReason != null ? finishReason : FinishReason.STOP).name().toLowerCase(Locale.ROOT);
    }

    private void writeJsonError(Context ctx, int status, String code, String message) throws IOException {
        ctx.status(status).contentType("application/json");
        ctx.result(mapper.writeValueAsString(new ErrorResponseDto(new ErrorDetailDto(code, message))));
    }

    /** {@code (httpStatus, code)} pair for one mapped exception; see {@link #mapError}. */
    private record ErrorMapping(int httpStatus, String code) {
    }

    /**
     * Single {@code (httpStatus, code)} lookup for every exception either
     * {@link #handle} or {@link #handleStreaming} can see. Used to be two
     * separate {@code instanceof} ladders ({@code httpStatusFor}/
     * {@code errorCodeFor}) covering the same exception hierarchy, which
     * could silently drift apart (e.g. one branch's status forgetting to
     * match its neighbor's code) — collapsing them into one lookup makes
     * that impossible.
     */
    private ErrorMapping mapError(Exception e) {
        if (e instanceof GuardBlockedException guardBlocked) {
            return new ErrorMapping(400, guardBlocked.reasonCode());
        }
        if (e instanceof NoSuchElementException) {
            return new ErrorMapping(400, "unknown_provider");
        }
        if (e instanceof IllegalStateException) {
            return new ErrorMapping(500, "routing_error");
        }
        if (e instanceof ToolCallLimitExceededException toolCallLimitExceeded) {
            return new ErrorMapping(500, toolCallLimitExceeded.code());
        }
        if (e instanceof ProviderAuthException authError) {
            return new ErrorMapping(authError.httpStatus() > 0 ? authError.httpStatus() : 401, "provider_auth_error");
        }
        if (e instanceof ProviderRateLimitException rateLimited) {
            return new ErrorMapping(rateLimited.httpStatus() > 0 ? rateLimited.httpStatus() : 429, "provider_rate_limited");
        }
        if (e instanceof ProviderTimeoutException) {
            return new ErrorMapping(504, "provider_timeout");
        }
        if (e instanceof ProviderException providerError) {
            // Was hardcoded to 502 regardless of what the provider actually
            // reported (e.g. a 404 for an unknown model would be masked as
            // "Bad Gateway"); ProviderAuthException/ProviderRateLimitException
            // right above already do this correctly, so the generic case now
            // matches them instead of being the odd one out.
            return new ErrorMapping(providerError.httpStatus() > 0 ? providerError.httpStatus() : 502, "provider_error");
        }
        return new ErrorMapping(500, "internal_error");
    }

    private ChatCompletionResponseDto toDto(CompletionResponse response) {
        ChatMessageDto message = new ChatMessageDto("assistant", response.content());
        ChatChoiceDto choice = new ChatChoiceDto(0, message, response.finishReason().name().toLowerCase(Locale.ROOT));
        UsageDto usage = new UsageDto(
                response.usage().promptTokens(), response.usage().completionTokens(), response.usage().totalTokens()
        );
        return new ChatCompletionResponseDto(
                response.id(), "chat.completion", Instant.now().getEpochSecond(), response.model(), List.of(choice), usage
        );
    }
}
