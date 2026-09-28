package dev.aegis4j.api.guard;

import dev.aegis4j.api.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuardContextTest {

    @Test
    void retrievedChunksIsEmptyWhenMetadataHasNoEntry() {
        GuardContext ctx = new GuardContext("req-1", "user-1", Map.of());

        assertThat(ctx.retrievedChunks()).isEmpty();
    }

    @Test
    void retrievedChunksIsEmptyWhenMetadataValueIsWrongType() {
        GuardContext ctx = new GuardContext("req-1", "user-1", Map.of(GuardContext.RETRIEVED_CHUNKS_KEY, "not-a-list"));

        assertThat(ctx.retrievedChunks()).isEmpty();
    }

    @Test
    void retrievedChunksReturnsWhatWasStoredUnderTheKey() {
        List<RetrievedChunk> chunks = List.of(new RetrievedChunk("fact", "doc-1", 0.8, Map.of()));
        GuardContext ctx = new GuardContext("req-1", "user-1", Map.of(GuardContext.RETRIEVED_CHUNKS_KEY, chunks));

        assertThat(ctx.retrievedChunks()).isEqualTo(chunks);
    }
}
