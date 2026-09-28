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

    @Test
    void rejectsNullText() {
        FixedSizeChunker chunker = new FixedSizeChunker(5, 0);

        assertThatThrownBy(() -> chunker.chunk(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void neverSplitsASurrogatePairAcrossChunkBoundaries() {
        // "abcd" + U+1F600 (a surrogate pair in UTF-16) + "efgh": with chunkSize=5,
        // a naive char-index cut lands exactly between the pair's two chars.
        String emoji = "😀";
        String text = "abcd" + emoji + "efgh";
        FixedSizeChunker chunker = new FixedSizeChunker(5, 0);

        List<String> chunks = chunker.chunk(text);

        assertThat(String.join("", chunks)).isEqualTo(text);
        for (String chunk : chunks) {
            assertThat(chunk.codePoints().count()).isGreaterThan(0);
            assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
        }
    }
}
