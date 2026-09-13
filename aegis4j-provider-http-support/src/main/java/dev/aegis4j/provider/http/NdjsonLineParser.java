package dev.aegis4j.provider.http;

import java.util.stream.Stream;

/** Ollama streams one JSON object per line; this just drops the blank lines around them. */
public final class NdjsonLineParser {

    private NdjsonLineParser() {
    }

    public static Stream<String> nonBlankLines(Stream<String> rawLines) {
        return rawLines.filter(line -> line != null && !line.isBlank());
    }
}
