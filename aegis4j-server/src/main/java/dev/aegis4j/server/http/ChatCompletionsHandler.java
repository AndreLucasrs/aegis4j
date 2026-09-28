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
 *
 * <p><b>Known limitation — no tool-calling round-trip over HTTP:</b>
 * {@link ChatMessageDto} carries only {@code role}/{@code content}, with no
 * {@code toolCalls} (on an assistant message) or {@code toolCallId} (on a
 * tool-result message). So even though {@link Aegis4jEngine} itself supports
 * tool calling end to end (see {@code Aegis4jEngine.Builder#tools}), this
 * handler can neither accept a client-supplied tool result on the request
 * nor echo {@code response.toolCalls()} back on the DTO — {@link #toDto}
 * only ever sets {@code content}. This is low priority today because no
 * {@code aegis4j-server} wiring calls {@code .tools(...)} on the engine it
 * builds, so no deployed server actually drives the tool-calling loop yet;
 * adding the DTO fields and the request/response mapping is the follow-up
 * once one does.
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
        } catch (GuardBlockedException e) {
            writeJsonError(ctx, 400, e.reasonCode(), e.getMessage());
        } catch (ToolCallLimitExceededException e) {
            writeJsonError(ctx, 500, e.code(), e.getMessage());
        } catch (IllegalStateException | NoSuchElementException | ProviderException e) {
            // Same exception set handleStreaming()'s initial catch already maps
            // (routing misconfigured, unregistered provider id, provider
            // rejected/timed out) — chat() can throw all of these too, and
            // before this they escaped here as an unhandled 500 from Javalin's
            // default error handler instead of the structured JSON error shape
            // every other failure on this endpoint gets.
            writeJsonError(ctx, httpStatusFor(e), errorCodeFor(e), e.getMessage());
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
     * SSE bytes have been written yet at this point.
     *
     * <p>Once the first byte is written the response is committed to 200
     * {@code text/event-stream} and the HTTP status can no longer change;
     * any exception raised while pulling further chunks (the same types
     * above, or anything else the provider's lazy stream throws) is instead
     * reported as a best-effort {@code data:} event using the same
     * {@code {"error": {"code": ..., "message": ...}}} shape, followed by
     * {@code data: [DONE]}.
     */
    private void handleStreaming(Context ctx, ChatRequest chatRequest) throws IOException {
        StreamedCompletion streamed;
        try {
            streamed = engine.chatStream(chatRequest);
        } catch (GuardBlockedException | IllegalStateException | NoSuchElementException | ProviderException e) {
            writeJsonError(ctx, httpStatusFor(e), errorCodeFor(e), e.getMessage());
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
        } catch (RuntimeException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            ErrorResponseDto errorDto = new ErrorResponseDto(new ErrorDetailDto(errorCodeFor(e), message));
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

    private int httpStatusFor(RuntimeException e) {
        if (e instanceof GuardBlockedException || e instanceof NoSuchElementException) {
            return 400;
        }
        if (e instanceof ProviderAuthException authError) {
            return authError.httpStatus() > 0 ? authError.httpStatus() : 401;
        }
        if (e instanceof ProviderRateLimitException rateLimited) {
            return rateLimited.httpStatus() > 0 ? rateLimited.httpStatus() : 429;
        }
        if (e instanceof ProviderTimeoutException) {
            return 504;
        }
        if (e instanceof ProviderException) {
            return 502;
        }
        return 500;
    }

    private String errorCodeFor(RuntimeException e) {
        if (e instanceof GuardBlockedException guardBlocked) {
            return guardBlocked.reasonCode();
        }
        if (e instanceof NoSuchElementException) {
            return "unknown_provider";
        }
        if (e instanceof IllegalStateException) {
            return "routing_error";
        }
        if (e instanceof ProviderAuthException) {
            return "provider_auth_error";
        }
        if (e instanceof ProviderRateLimitException) {
            return "provider_rate_limited";
        }
        if (e instanceof ProviderTimeoutException) {
            return "provider_timeout";
        }
        if (e instanceof ProviderException) {
            return "provider_error";
        }
        return "internal_error";
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
