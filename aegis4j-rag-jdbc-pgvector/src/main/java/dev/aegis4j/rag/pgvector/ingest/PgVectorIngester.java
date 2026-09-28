package dev.aegis4j.rag.pgvector.ingest;

import com.pgvector.PGvector;
import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.rag.pgvector.PgVectorRetrieverConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Loader → chunker → {@link Embedder} → pgvector pipeline. Writes rows in the
 * exact shape {@code PgVectorRetriever} expects to read back: same table and
 * id/content/embedding columns, taken from the same
 * {@link PgVectorRetrieverConfig} passed to the retriever, so ingestion and
 * retrieval never drift apart on schema.
 */
public final class PgVectorIngester {

    private final DataSource dataSource;
    private final Embedder embedder;
    private final PgVectorRetrieverConfig config;
    private final Chunker chunker;

    public PgVectorIngester(DataSource dataSource, Embedder embedder, PgVectorRetrieverConfig config, Chunker chunker) {
        this.dataSource = dataSource;
        this.embedder = embedder;
        this.config = config;
        this.chunker = chunker;
    }

    /**
     * Loads, chunks, embeds and stores every document the loader produces.
     *
     * @return the number of chunks written
     */
    public int ingest(DocumentLoader loader) {
        return ingest(loader.load());
    }

    /**
     * Chunks, embeds and stores the given documents.
     *
     * @return the number of chunks written
     */
    public int ingest(Iterable<Document> documents) {
        try (Connection connection = dataSource.getConnection()) {
            PGvector.addVectorType(connection);
            try (PreparedStatement statement = connection.prepareStatement(buildInsert())) {
                int chunkCount = 0;
                for (Document document : documents) {
                    chunkCount += writeChunks(statement, document);
                }
                statement.executeBatch();
                return chunkCount;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to ingest documents into pgvector table " + config.table(), e);
        }
    }

    private int writeChunks(PreparedStatement statement, Document document) throws SQLException {
        List<String> chunks = chunker.chunk(document.content());
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            statement.setString(1, document.id() + "#" + i);
            statement.setString(2, chunkText);
            statement.setObject(3, new PGvector(embedder.embed(chunkText)));
            statement.addBatch();
        }
        return chunks.size();
    }

    private String buildInsert() {
        return "INSERT INTO " + config.table() + " (" + config.idColumn() + ", " + config.contentColumn() + ", "
                + config.embeddingColumn() + ") VALUES (?, ?, ?)";
    }
}
