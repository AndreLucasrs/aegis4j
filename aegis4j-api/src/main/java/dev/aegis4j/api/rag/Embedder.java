package dev.aegis4j.api.rag;

/** Text-to-vector contract, independent of any specific vector store or model. */
public interface Embedder {

    float[] embed(String text);

    int dimensions();
}
