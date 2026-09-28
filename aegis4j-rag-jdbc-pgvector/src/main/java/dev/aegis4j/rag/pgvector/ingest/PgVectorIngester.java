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
import java.util.Collection;
import java.util.List;

/**
 * Loader → chunker → {@link Embedder} → pgvector pipeline. Writes rows in the
 * exact shape {@code PgVectorRetriever} expects to read back: same table and
 * id/content/embedding columns, taken from the same
 * {@link PgVectorRetrieverConfig} passed to the retriever, so ingestion and
 * retrieval never drift apart on schema.
 *
 * <p>Documents are processed one at a time: a document's chunks are embedded
 * and written in slices of at most {@code batchFlushSize}, instead of
 * embedding the whole corpus into memory before the first row is ever
 * written. This keeps memory bounded for a large ingestion run — only one
 * document's in-flight slice of vectors is ever held at once — and a
 * mid-corpus failure only loses the batch in flight, not work already
 * flushed. A JDBC connection (and a slot in whatever pool backs the
 * {@link DataSource}) is opened once for the whole {@link #ingest} call, not
 * per document.
 *
 * <p>Writes use {@code ON CONFLICT ... DO UPDATE}, so re-running ingestion
 * over the same documents (a scheduled re-sync, a retry, a content update)
 * replaces existing rows instead of aborting the whole batch on a duplicate
 * key. This requires the configured {@code idColumn} to carry a unique or
 * primary key constraint. After a document's chunks are upserted, any row
 * left over from a previous ingestion of that same document — one whose id
 * still starts with {@code <documentId>#} but is no longer among the ids the
 * new chunk list produces, e.g. because the document shrank from 5 chunks to
 * 2 — is deleted, so reingestion actually replaces a document's rows instead
 * of only ever adding to or updating them.
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
     * @param batchFlushSize how many chunks to embed and accumulate in a single JDBC batch
     *                       before flushing (executeBatch + clearBatch) and moving on; also the
     *                       most in-flight embedded chunks (vectors included) ever held in memory
     *                       at once, since chunks are embedded incrementally into each slice
     *                       rather than the whole document/corpus being embedded up front
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
        if (documents instanceof Collection<?> collection && collection.isEmpty()) {
            // Cheap, common-case (List-returning DocumentLoader) short circuit: skips both the
            // dimension pre-flight and opening a write connection when there is no work at all.
            return 0;
        }

        validateEmbeddingDimension();

        // The write connection/statements are opened lazily, on the first document that passes
        // validateDocument, and reused for every document after that — not one connection per
        // document, and not before an earlier invalid document has had a chance to fail fast
        // without ever touching the database (see WriteSession.ensureOpen).
        try (WriteSession session = new WriteSession()) {
            int totalChunks = 0;
            for (Document document : documents) {
                validateDocument(document);
                totalChunks += session.ingestDocument(document);
            }
            return totalChunks;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to ingest documents into pgvector table " + config.table(), e);
        }
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

    private String buildUpsert() {
        return "INSERT INTO " + config.table() + " (" + config.idColumn() + ", " + config.contentColumn() + ", "
                + config.embeddingColumn() + ") VALUES (?, ?, ?) "
                + "ON CONFLICT (" + config.idColumn() + ") DO UPDATE SET "
                + config.contentColumn() + " = EXCLUDED." + config.contentColumn() + ", "
                + config.embeddingColumn() + " = EXCLUDED." + config.embeddingColumn();
    }

    /**
     * Deletes rows whose id belongs to a document (matched by the {@code <documentId>#} prefix
     * convention {@link #ingest} writes ids with) but is not one of that document's current chunk
     * ids — i.e. rows left over from a previous ingestion of the same document that produced more
     * chunks than the new content does. {@code <> ALL(?)} against a bound {@code text[]} lets one
     * prepared statement handle any number of current ids, including zero (an empty array makes
     * {@code <> ALL(...)} true for every row, correctly deleting every chunk of a document whose
     * new content chunks to nothing).
     */
    private String buildOrphanDelete() {
        return "DELETE FROM " + config.table() + " WHERE " + config.idColumn() + " LIKE ? ESCAPE '\\' AND "
                + config.idColumn() + " <> ALL(?)";
    }

    /**
     * Builds the {@code <documentId>#} LIKE prefix pattern, escaping the id's own {@code %} and
     * {@code _} characters (e.g. a filename-derived id containing an underscore) so they are
     * matched literally instead of acting as SQL wildcards — otherwise this delete could match,
     * and remove, chunks belonging to an unrelated document.
     */
    private static String likePattern(String documentId) {
        return documentId.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "#%";
    }

    /**
     * Owns the single JDBC connection and the two prepared statements (upsert, orphan delete)
     * used across every document in one {@link #ingest} call. Opened lazily on the first document
     * that reaches {@link #ingestDocument} — never for a document that {@code validateDocument}
     * rejects first, and never at all for an ingestion that turns out to have no valid documents.
     */
    private final class WriteSession implements AutoCloseable {

        private Connection connection;
        private PreparedStatement upsert;
        private PreparedStatement deleteOrphans;

        /**
         * Embeds and writes one document's chunks in slices of at most {@code batchFlushSize} —
         * a chunk is only embedded right before it is added to the pending JDBC batch, so at most
         * one slice's worth of vectors is ever held in memory, never the whole document (let
         * alone the whole corpus) — then deletes that document's orphaned rows, if any.
         */
        int ingestDocument(Document document) throws SQLException {
            ensureOpen();

            List<String> chunks = chunker.chunk(document.content());
            List<String> currentIds = new ArrayList<>(chunks.size());
            int pending = 0;
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);
                String id = document.id() + "#" + i;
                currentIds.add(id);
                upsert.setString(1, id);
                upsert.setString(2, chunkText);
                upsert.setObject(3, new PGvector(embedder.embed(chunkText)));
                upsert.addBatch();
                pending++;
                if (pending >= batchFlushSize) {
                    upsert.executeBatch();
                    upsert.clearBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                upsert.executeBatch();
                upsert.clearBatch();
            }

            deleteOrphans.setString(1, likePattern(document.id()));
            deleteOrphans.setArray(2, connection.createArrayOf("text", currentIds.toArray(new String[0])));
            deleteOrphans.executeUpdate();

            return chunks.size();
        }

        private void ensureOpen() throws SQLException {
            if (connection == null) {
                connection = dataSource.getConnection();
                PGvector.addVectorType(connection);
                upsert = connection.prepareStatement(buildUpsert());
                deleteOrphans = connection.prepareStatement(buildOrphanDelete());
            }
        }

        @Override
        public void close() throws SQLException {
            if (connection == null) {
                return;
            }
            // Close every resource even if an earlier one fails, statements before the connection,
            // surfacing the first failure and suppressing the rest rather than losing them.
            SQLException failure = closeQuietly(deleteOrphans, null);
            failure = closeQuietly(upsert, failure);
            failure = closeQuietly(connection, failure);
            if (failure != null) {
                throw failure;
            }
        }

        private SQLException closeQuietly(AutoCloseable resource, SQLException previousFailure) {
            try {
                resource.close();
                return previousFailure;
            } catch (Exception e) {
                SQLException failure = e instanceof SQLException sqlException ? sqlException : new SQLException(e);
                if (previousFailure != null) {
                    previousFailure.addSuppressed(failure);
                    return previousFailure;
                }
                return failure;
            }
        }
    }
}
