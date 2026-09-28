package dev.aegis4j.rag.pgvector.ingest;

import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.rag.pgvector.PgVectorRetrieverConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-tests only the SQL-building and batching logic against mocked
 * {@code java.sql.*} — no real Postgres/pgvector involved (see
 * {@code PgVectorIngesterIntegrationTest}, tagged {@code requires-docker},
 * for the real-database, end-to-end-with-the-retriever version).
 */
class PgVectorIngesterTest {

    @Test
    void chunksEmbedsAndBatchesEachDocument() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PGConnection pgConnection = mock(PGConnection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class); // dimension-check query; next() defaults to false, i.e. "can't determine"
        Embedder embedder = mock(Embedder.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(embedder.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        PgVectorIngester ingester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(5, 0)
        );

        int chunkCount = ingester.ingest(List.of(new Document("doc-1", "abcdefghij", Map.of())));

        assertThat(chunkCount).isEqualTo(2);
        verify(statement, times(2)).addBatch();
        verify(statement).executeBatch();
        verify(statement).setString(1, "doc-1#0");
        verify(statement).setString(2, "abcde");
        verify(statement).setString(1, "doc-1#1");
        verify(statement).setString(2, "fghij");
        verify(statement).executeUpdate(); // the post-upsert orphan-delete for doc-1

        // dimension check + upsert + orphan-delete: three distinct prepared statements.
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, times(3)).prepareStatement(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues())
                .anySatisfy(sql -> assertThat(sql)
                        .contains("INSERT INTO document_chunks")
                        .contains("id")
                        .contains("content")
                        .contains("embedding")
                        .contains("ON CONFLICT"))
                .anySatisfy(sql -> assertThat(sql)
                        .contains("DELETE FROM document_chunks")
                        .contains("LIKE")
                        .contains("ALL"));
    }

    @Test
    void producesNoChunksForEmptyDocumentListAndSkipsAllJdbcWork() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PGConnection pgConnection = mock(PGConnection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        Embedder embedder = mock(Embedder.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);

        PgVectorIngester ingester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(5, 0)
        );

        int chunkCount = ingester.ingest(List.of());

        assertThat(chunkCount).isZero();
        verify(statement, never()).executeBatch();
        // An empty document list is an early-return: no dimension pre-check, no write connection.
        verify(dataSource, never()).getConnection();
    }

    @Test
    void flushesPeriodicallyOnceTheBatchSizeIsReached() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PGConnection pgConnection = mock(PGConnection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        Embedder embedder = mock(Embedder.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(embedder.embed(anyString())).thenReturn(new float[]{0.1f});

        // batchFlushSize=1 forces a flush after every single chunk.
        PgVectorIngester ingester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(1, 0), 1
        );

        int chunkCount = ingester.ingest(List.of(new Document("doc-1", "ab", Map.of())));

        assertThat(chunkCount).isEqualTo(2);
        verify(statement, times(2)).addBatch();
        verify(statement, times(2)).executeBatch();
        verify(statement, times(2)).clearBatch();
    }

    @Test
    void embedsIncrementallyInsteadOfEmbeddingTheWholeDocumentBeforeAnyFlush() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PGConnection pgConnection = mock(PGConnection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        Embedder embedder = mock(Embedder.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(embedder.embed(anyString())).thenReturn(new float[]{0.1f});

        // batchFlushSize=1: if ingest() embedded the whole document up front (the bug this
        // regression-tests), every embed() call would happen before the first executeBatch();
        // the fix interleaves one embed() per chunk with the flush it feeds.
        PgVectorIngester ingester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(1, 0), 1
        );

        ingester.ingest(List.of(new Document("doc-1", "ab", Map.of())));

        InOrder order = inOrder(embedder, statement);
        order.verify(embedder).embed("a");
        order.verify(statement).executeBatch();
        order.verify(embedder).embed("b");
        order.verify(statement).executeBatch();
    }

    @Test
    void deletesOrphanedRowsLeftOverWhenADocumentNowProducesFewerChunks() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PGConnection pgConnection = mock(PGConnection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        Embedder embedder = mock(Embedder.class);
        Array idsArray = mock(Array.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(embedder.embed(anyString())).thenReturn(new float[]{0.1f});
        when(connection.createArrayOf(eq("text"), any())).thenReturn(idsArray);

        PgVectorIngester ingester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(5, 0)
        );

        // "doc-1" now chunks to a single chunk; a previous, longer-content ingestion could have
        // left doc-1#1, doc-1#2, ... behind, which must now be treated as orphaned and removed.
        ingester.ingest(List.of(new Document("doc-1", "abcde", Map.of())));

        ArgumentCaptor<String> setStringCaptor = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).setString(eq(1), setStringCaptor.capture());
        assertThat(setStringCaptor.getAllValues()).contains("doc-1#%");

        ArgumentCaptor<String[]> idsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(connection).createArrayOf(eq("text"), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly("doc-1#0");

        verify(statement).setArray(2, idsArray);
        verify(statement).executeUpdate();
    }

    @Test
    void escapesLikeWildcardsInTheDocumentIdBeforeBuildingTheOrphanDeletePattern() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PGConnection pgConnection = mock(PGConnection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        Embedder embedder = mock(Embedder.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(embedder.embed(anyString())).thenReturn(new float[]{0.1f});

        PgVectorIngester ingester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(5, 0)
        );

        // "some_file.txt" is a very plausible filename-derived id; the underscore must not act
        // as a LIKE wildcard, or the delete could match (and remove) an unrelated document's rows.
        ingester.ingest(List.of(new Document("some_file.txt", "abcde", Map.of())));

        ArgumentCaptor<String> setStringCaptor = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).setString(eq(1), setStringCaptor.capture());
        assertThat(setStringCaptor.getAllValues()).contains("some\\_file.txt#%");
    }

    @Test
    void rejectsNonPositiveBatchFlushSize() {
        DataSource dataSource = mock(DataSource.class);
        Embedder embedder = mock(Embedder.class);

        assertThatThrownBy(() -> new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(5, 0), 0
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDocumentWithBlankId() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Embedder embedder = mock(Embedder.class);
        // Dimension pre-check is best-effort: an unreachable DataSource here just means it's skipped.
        // The write connection must never even be attempted, since validation fails first.
        when(dataSource.getConnection()).thenThrow(new SQLException("unavailable in this test"));

        PgVectorIngester ingester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(5, 0)
        );

        assertThatThrownBy(() -> ingester.ingest(List.of(new Document(" ", "content", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        verifyNoInteractions(embedder);
    }

    @Test
    void rejectsDocumentWithNullContent() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Embedder embedder = mock(Embedder.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("unavailable in this test"));

        PgVectorIngester ingester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(5, 0)
        );

        assertThatThrownBy(() -> ingester.ingest(List.of(new Document("doc-1", null, Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
        verifyNoInteractions(embedder);
    }

    @Test
    void failsFastWhenEmbedderDimensionDoesNotMatchTheColumn() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        Embedder embedder = mock(Embedder.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(1536);
        when(embedder.dimensions()).thenReturn(3);

        PgVectorIngester ingester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(5, 0)
        );

        assertThatThrownBy(() -> ingester.ingest(List.of(new Document("doc-1", "some content", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1536")
                .hasMessageContaining("3");
        verify(embedder, never()).embed(anyString());
    }
}
