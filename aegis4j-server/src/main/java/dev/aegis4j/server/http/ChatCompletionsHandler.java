package dev.aegis4j.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.provider.Role;
import dev.aegis4j.core.engine.Aegis4jEngine;
import dev.aegis4j.core.engine.ChatRequest;
import dev.aegis4j.core.guard.GuardBlockedException;
import dev.aegis4j.server.dto.ChatChoiceDto;
import dev.aegis4j.server.dto.ChatCompletionRequestDto;
import dev.aegis4j.server.dto.ChatCompletionResponseDto;
import dev.aegis4j.server.dto.ChatMessageDto;
import dev.aegis4j.server.dto.UsageDto;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code POST /v1/chat/completions}, OpenAI-compatible request/response shape, non-streaming only in v0.1. */
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
