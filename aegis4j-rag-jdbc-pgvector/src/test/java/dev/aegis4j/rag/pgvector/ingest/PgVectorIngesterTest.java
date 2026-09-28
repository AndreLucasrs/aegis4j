package dev.aegis4j.rag.pgvector.ingest;

import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.rag.pgvector.PgVectorRetrieverConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
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

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues())
                .anySatisfy(sql -> assertThat(sql)
                        .contains("INSERT INTO document_chunks")
                        .contains("id")
                        .contains("content")
                        .contains("embedding")
                        .contains("ON CONFLICT"));
    }

    @Test
    void producesNoChunksForEmptyDocumentListAndSkipsTheFinalFlush() throws SQLException {
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
