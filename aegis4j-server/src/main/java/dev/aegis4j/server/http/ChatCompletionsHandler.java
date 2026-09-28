package dev.aegis4j.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.Role;
import dev.aegis4j.core.engine.Aegis4jEngine;
import dev.aegis4j.core.engine.ChatRequest;
import dev.aegis4j.core.guard.GuardBlockedException;
import dev.aegis4j.server.dto.ChatChoiceDto;
import dev.aegis4j.server.dto.ChatChunkChoiceDto;
import dev.aegis4j.server.dto.ChatCompletionChunkDto;
import dev.aegis4j.server.dto.ChatCompletionRequestDto;
import dev.aegis4j.server.dto.ChatCompletionResponseDto;
import dev.aegis4j.server.dto.ChatDeltaDto;
import dev.aegis4j.server.dto.ChatMessageDto;
import dev.aegis4j.server.dto.UsageDto;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            ctx.status(400).contentType("application/json");
            ctx.result(mapper.writeValueAsString(Map.of("error", "messages must not be empty")));
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
            handleStreaming(ctx, chatRequest, requestDto.model());
            return;
        }

        try {
            CompletionResponse response = engine.chat(chatRequest);
            ctx.contentType("application/json");
            ctx.result(mapper.writeValueAsString(toDto(response)));
        } catch (GuardBlockedException e) {
            ctx.status(400).contentType("application/json");
            ctx.result(mapper.writeValueAsString(Map.of(
                    "error", Map.of("code", e.reasonCode(), "message", e.getMessage())
            )));
        }
    }

    /**
     * {@link Aegis4jEngine#chatStream} still runs input guards, retrieval and
     * routing synchronously before returning the {@link Stream}, so a
     * {@link GuardBlockedException} is caught here the same way as in the
     * non-streaming path — no SSE bytes have been written yet at that point.
     * Once the first chunk is written the response is committed to 200
     * {@code text/event-stream}; a failure mid-stream is reported as a best
     * effort {@code data:} error event followed by {@code [DONE]} rather than
     * an HTTP error status, since the status line can no longer change.
     */
    private void handleStreaming(Context ctx, ChatRequest chatRequest, String requestedModel) throws IOException {
        Stream<CompletionChunk> chunks;
        try {
            chunks = engine.chatStream(chatRequest);
        } catch (GuardBlockedException e) {
            ctx.status(400).contentType("application/json");
            ctx.result(mapper.writeValueAsString(Map.of(
                    "error", Map.of("code", e.reasonCode(), "message", e.getMessage())
            )));
            return;
        }

        String completionId = "chatcmpl-" + chatRequest.requestId();
        String responseModel = requestedModel != null ? requestedModel : "unknown";

        ctx.status(200);
        ctx.contentType("text/event-stream");
        ctx.header("Cache-Control", "no-cache");
        ctx.header("Connection", "keep-alive");
        ctx.header("X-Accel-Buffering", "no");

        OutputStream out = ctx.outputStream();
        boolean roleSent = false;
        try (chunks) {
            for (Iterator<CompletionChunk> it = chunks.iterator(); it.hasNext(); ) {
                CompletionChunk chunk = it.next();
                writeEvent(out, mapper.writeValueAsString(toChunkDto(chunk, completionId, responseModel, !roleSent)));
                roleSent = true;
            }
        } catch (RuntimeException e) {
            writeEvent(out, mapper.writeValueAsString(Map.of(
                    "error", Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
            )));
        }
        writeDone(out);
    }

    private ChatCompletionChunkDto toChunkDto(CompletionChunk chunk, String id, String model, boolean includeRole) {
        ChatDeltaDto delta = new ChatDeltaDto(includeRole ? "assistant" : null, chunk.done() ? null : chunk.deltaContent());
        ChatChunkChoiceDto choice = new ChatChunkChoiceDto(0, delta, chunk.done() ? "stop" : null);
        return new ChatCompletionChunkDto(id, "chat.completion.chunk", Instant.now().getEpochSecond(), model, List.of(choice));
    }

    private void writeEvent(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void writeDone(OutputStream out) throws IOException {
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
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
