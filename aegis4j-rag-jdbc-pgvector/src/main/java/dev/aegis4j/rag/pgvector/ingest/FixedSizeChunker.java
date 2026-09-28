package dev.aegis4j.rag.pgvector.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Splits text into fixed-length character windows, each overlapping the previous one by {@code overlap} characters. */
public final class FixedSizeChunker implements Chunker {

    private final int chunkSize;
    private final int overlap;

    public FixedSizeChunker(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive, got " + chunkSize);
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be in [0, chunkSize), got " + overlap);
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<String> chunk(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int step = chunkSize - overlap;
        for (int rawStart = 0; rawStart < text.length(); rawStart += step) {
            int start = avoidSplittingSurrogatePair(text, rawStart);
            int end = avoidSplittingSurrogatePair(text, Math.min(rawStart + chunkSize, text.length()));
            if (end <= start) {
                // Only reachable in pathological tiny-window cases; widen rather than emit an empty/invalid chunk.
                end = Math.min(start + 2, text.length());
            }
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
        }
        return List.copyOf(chunks);
    }

    /**
     * Nudges a split index one character left when it falls between a high
     * and low surrogate, so a chunk boundary never cuts a UTF-16 surrogate
     * pair (e.g. an emoji or other non-BMP character) in half — Postgres
     * rejects the resulting invalid byte sequence outright. Applying this to
     * every raw boundary keeps adjacent chunks gap-free and duplicate-free,
     * since the same raw index always maps to the same adjusted index.
     */
    private static int avoidSplittingSurrogatePair(String text, int index) {
        if (index > 0 && index < text.length()
                && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index))) {
            return index - 1;
        }
        return index;
    }
}
