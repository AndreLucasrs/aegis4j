package dev.aegis4j.rag.pgvector.ingest;

import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.rag.pgvector.PgVectorRetrieverConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        Embedder embedder = mock(Embedder.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
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
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("INSERT INTO document_chunks")
                .contains("id")
                .contains("content")
                .contains("embedding");
    }

    @Test
    void producesNoChunksForEmptyDocumentList() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PGConnection pgConnection = mock(PGConnection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Embedder embedder = mock(Embedder.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        PgVectorIngester ingester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks"), new FixedSizeChunker(5, 0)
        );

        int chunkCount = ingester.ingest(List.of());

        assertThat(chunkCount).isZero();
        verify(statement).executeBatch();
    }
}
