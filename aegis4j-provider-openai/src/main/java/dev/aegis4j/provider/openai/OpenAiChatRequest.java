package dev.aegis4j.provider.openai;

import java.util.List;

record OpenAiChatRequest(String model, List<OpenAiMessage> messages, boolean stream, Double temperature, Integer maxTokens) {
}
