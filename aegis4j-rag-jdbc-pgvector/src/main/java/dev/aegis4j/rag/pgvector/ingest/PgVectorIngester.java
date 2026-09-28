package dev.aegis4j.rag.pgvector.ingest;

import com.pgvector.PGvector;
import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.rag.pgvector.PgVectorRetrieverConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Loader → chunker → {@link Embedder} → pgvector pipeline. Writes rows in the
 * exact shape {@code PgVectorRetriever} expects to read back: same table and
 * id/content/embedding columns, taken from the same
 * {@link PgVectorRetrieverConfig} passed to the retriever, so ingestion and
 * retrieval never drift apart on schema.
 *
 * <p>Every chunk is embedded up front, before any JDBC connection is opened
 * — a connection (and a slot in whatever pool backs the {@link DataSource})
 * is only held for the write step, not for the duration of a slow/remote
 * embedding call per chunk.
 *
 * <p>Writes use {@code ON CONFLICT ... DO UPDATE}, so re-running ingestion
 * over the same documents (a scheduled re-sync, a retry, a content update)
 * replaces existing rows instead of aborting the whole batch on a duplicate
 * key. This requires the configured {@code idColumn} to carry a unique or
 * primary key constraint.
 */
public final class PgVectorIngester {

    private static final int DEFAULT_BATCH_FLUSH_SIZE = 500;

    private final DataSource dataSource;
    private final Embedder embedder;
    private final PgVectorRetrieverConfig config;
    private final Chunker chunker;
    private final int batchFlushSize;

    public PgVectorIngester(DataSource dataSource, Embedder embedder, PgVectorRetrieverConfig config, Chunker chunker) {
        this(dataSource, embedder, config, chunker, DEFAULT_BATCH_FLUSH_SIZE);
    }

    /**
     * @param batchFlushSize how many chunks to accumulate in a single JDBC batch before
     *                       flushing (executeBatch + clearBatch); keeps memory bounded and
     *                       a mid-corpus failure from discarding an entire large ingestion run
     */
    public PgVectorIngester(DataSource dataSource, Embedder embedder, PgVectorRetrieverConfig config, Chunker chunker,
                             int batchFlushSize) {
        if (batchFlushSize <= 0) {
            throw new IllegalArgumentException("batchFlushSize must be positive, got " + batchFlushSize);
        }
        this.dataSource = dataSource;
        this.embedder = embedder;
        this.config = config;
        this.chunker = chunker;
        this.batchFlushSize = batchFlushSize;
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
        validateEmbeddingDimension();

        List<EmbeddedChunk> embeddedChunks = new ArrayList<>();
        for (Document document : documents) {
            validateDocument(document);
            List<String> chunks = chunker.chunk(document.content());
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                embeddedChunks.add(new EmbeddedChunk(document.id() + "#" + i, chunkText, embedder.embed(chunkText)));
            }
        }

        writeChunks(embeddedChunks);
        return embeddedChunks.size();
    }

    private void validateDocument(Document document) {
        if (document.id() == null || document.id().isBlank()) {
            throw new IllegalArgumentException("Document id must not be null or blank");
        }
        if (document.content() == null) {
            throw new IllegalArgumentException("Document content must not be null (document id: " + document.id() + ")");
        }
    }

    /**
     * Best-effort pre-flight check: compares {@link Embedder#dimensions()}
     * against the target vector column's declared dimension, so a mismatched
     * embedder/table pairing fails before any (potentially costly) embedding
     * call rather than after the whole corpus has already been embedded.
     * Silently skipped when the column's dimension can't be determined — an
     * unconstrained {@code vector} column, or the introspection query itself
     * failing — in which case a real mismatch still only surfaces as a
     * Postgres error once the write is attempted.
     */
    private void validateEmbeddingDimension() {
        String sql = "SELECT atttypmod FROM pg_attribute WHERE attrelid = to_regclass(?) AND attname = ? AND NOT attisdropped";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, config.table());
            statement.setString(2, config.embeddingColumn());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    int columnDimensions = resultSet.getInt(1);
                    if (columnDimensions > 0 && columnDimensions != embedder.dimensions()) {
                        throw new IllegalArgumentException(
                                "Embedder produces " + embedder.dimensions() + "-dimensional vectors but "
                                        + config.table() + "." + config.embeddingColumn()
                                        + " is declared as vector(" + columnDimensions + ")");
                    }
                }
            }
        } catch (SQLException e) {
            // Best-effort only — see the javadoc above.
        }
    }

    private void writeChunks(List<EmbeddedChunk> embeddedChunks) {
        try (Connection connection = dataSource.getConnection()) {
            PGvector.addVectorType(connection);
            try (PreparedStatement statement = connection.prepareStatement(buildUpsert())) {
                int pending = 0;
                for (EmbeddedChunk chunk : embeddedChunks) {
                    statement.setString(1, chunk.id());
                    statement.setString(2, chunk.content());
                    statement.setObject(3, new PGvector(chunk.vector()));
                    statement.addBatch();
                    pending++;
                    if (pending >= batchFlushSize) {
                        statement.executeBatch();
                        statement.clearBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    statement.executeBatch();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to ingest documents into pgvector table " + config.table(), e);
        }
    }

    private String buildUpsert() {
        return "INSERT INTO " + config.table() + " (" + config.idColumn() + ", " + config.contentColumn() + ", "
                + config.embeddingColumn() + ") VALUES (?, ?, ?) "
                + "ON CONFLICT (" + config.idColumn() + ") DO UPDATE SET "
                + config.contentColumn() + " = EXCLUDED." + config.contentColumn() + ", "
                + config.embeddingColumn() + " = EXCLUDED." + config.embeddingColumn();
    }

    private record EmbeddedChunk(String id, String content, float[] vector) {
    }
}
