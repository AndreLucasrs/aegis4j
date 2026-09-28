package dev.aegis4j.rag.pgvector.ingest;

import dev.aegis4j.api.rag.Embedder;

import java.util.Locale;

/**
 * Deterministic, dependency-free fake embedder for tests: hashes each word of
 * the text into a fixed-size vector so that texts sharing vocabulary end up
 * closer together, without pulling in a real embedding model.
 */
final class HashEmbedder implements Embedder {

    private final int dimensions;

    HashEmbedder(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        for (String word : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (word.isBlank()) {
                continue;
            }
            vector[Math.floorMod(word.hashCode(), dimensions)] += 1f;
        }
        return vector;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
