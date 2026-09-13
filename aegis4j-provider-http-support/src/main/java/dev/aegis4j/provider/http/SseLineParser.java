package dev.aegis4j.provider.http;

import java.util.stream.Stream;

/** Extracts {@code data: } payloads from an SSE stream — shared by any provider using that framing (OpenAI, Anthropic). */
public final class SseLineParser {

    private SseLineParser() {
    }

    public static Stream<String> dataPayloads(Stream<String> rawLines) {
        return rawLines
                .filter(line -> line != null && line.startsWith("data:"))
                .map(line -> line.substring(5).strip())
                .filter(payload -> !payload.isEmpty());
    }
}
