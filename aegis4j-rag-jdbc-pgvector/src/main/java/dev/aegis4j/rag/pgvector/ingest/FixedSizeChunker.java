package dev.aegis4j.rag.pgvector.ingest;

import java.util.ArrayList;
import java.util.List;

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
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int step = chunkSize - overlap;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
        }
        return List.copyOf(chunks);
    }
}
