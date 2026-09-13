package dev.aegis4j.testkit;

import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.rag.Retriever;

import java.util.ArrayList;
import java.util.List;

/** In-process {@link Retriever} test double — records every query it receives, like {@link FakeProvider}. */
public final class FakeRetriever implements Retriever {

    private final List<RetrievedChunk> chunks;
    private final List<String> receivedQueries = new ArrayList<>();

    private FakeRetriever(List<RetrievedChunk> chunks) {
        this.chunks = chunks;
    }

    public static FakeRetriever withChunks(List<RetrievedChunk> chunks) {
        return new FakeRetriever(chunks);
    }

    public List<String> receivedQueries() {
        return List.copyOf(receivedQueries);
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        receivedQueries.add(query);
        return chunks;
    }
}
