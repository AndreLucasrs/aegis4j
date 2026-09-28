package dev.aegis4j.server.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Raw SSE byte framing ({@code data: <payload>\n\n}), kept separate from
 * chunk-to-DTO mapping and error handling in {@link ChatCompletionsHandler}
 * so each concern can be read, fixed and (if it ever needs its own unit
 * test) tested in isolation.
 */
final class SseWriter {

    private static final byte[] DATA_PREFIX = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TERMINATOR = "\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DONE_PAYLOAD = "[DONE]".getBytes(StandardCharsets.UTF_8);

    private final OutputStream out;

    SseWriter(OutputStream out) {
        this.out = out;
    }

    /** Writes one {@code data: <json>\n\n} event and flushes so the client sees it immediately. */
    void writeJson(byte[] jsonPayload) throws IOException {
        out.write(DATA_PREFIX);
        out.write(jsonPayload);
        out.write(TERMINATOR);
        out.flush();
    }

    /** Writes the terminal {@code data: [DONE]\n\n} event. */
    void writeDone() throws IOException {
        out.write(DATA_PREFIX);
        out.write(DONE_PAYLOAD);
        out.write(TERMINATOR);
        out.flush();
    }
}
