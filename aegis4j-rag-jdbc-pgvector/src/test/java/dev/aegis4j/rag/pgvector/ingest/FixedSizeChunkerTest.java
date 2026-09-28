package dev.aegis4j.rag.pgvector.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedSizeChunkerTest {

    @Test
    void splitsTextIntoOverlappingWindows() {
        FixedSizeChunker chunker = new FixedSizeChunker(5, 2);

        List<String> chunks = chunker.chunk("abcdefghij");

        assertThat(chunks).containsExactly("abcde", "defgh", "ghij");
    }

    @Test
    void returnsWholeTextWhenShorterThanChunkSize() {
        FixedSizeChunker chunker = new FixedSizeChunker(100, 10);

        List<String> chunks = chunker.chunk("short text");

        assertThat(chunks).containsExactly("short text");
    }

    @Test
    void returnsNoChunksForEmptyText() {
        FixedSizeChunker chunker = new FixedSizeChunker(5, 1);

        assertThat(chunker.chunk("")).isEmpty();
    }

    @Test
    void rejectsNonPositiveChunkSize() {
        assertThatThrownBy(() -> new FixedSizeChunker(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlapNotSmallerThanChunkSize() {
        assertThatThrownBy(() -> new FixedSizeChunker(5, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeOverlap() {
        assertThatThrownBy(() -> new FixedSizeChunker(5, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
