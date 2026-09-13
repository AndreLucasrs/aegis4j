package dev.aegis4j.rag.pgvector;

public record PgVectorRetrieverConfig(
        String table,
        String contentColumn,
        String embeddingColumn,
        String idColumn,
        String distanceOperator
) {

    public static PgVectorRetrieverConfig defaults(String table) {
        return new PgVectorRetrieverConfig(table, "content", "embedding", "id", "<=>");
    }
}
