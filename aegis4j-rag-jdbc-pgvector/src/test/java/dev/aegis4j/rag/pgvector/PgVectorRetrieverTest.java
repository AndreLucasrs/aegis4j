package dev.aegis4j.rag.pgvector;

import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.api.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-tests only the SQL-building and row-mapping logic against mocked
 * {@code java.sql.*} — no real Postgres/pgvector involved (see
 * {@code PgVectorRetrieverIntegrationTest}, tagged {@code requires-docker},
 * for the real-database version). Mockito is the pragmatic exception to this
 * repo's "prefer fakes" convention here: hand-writing a fake
 * {@code java.sql.Connection} is impractical given the interface's size.
 */
class PgVectorRetrieverTest {

    @Test
    void buildsQueryWithConfiguredColumnsAndMapsResults() throws SQLException {
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
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("content")).thenReturn("hello world");
        when(resultSet.getString("id")).thenReturn("chunk-1");
        when(resultSet.getDouble("score")).thenReturn(0.87);
        when(embedder.embed("query text")).thenReturn(new float[]{0.1f, 0.2f});

        PgVectorRetriever retriever = new PgVectorRetriever(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("document_chunks")
        );

        List<RetrievedChunk> chunks = retriever.retrieve("query text", 5);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("hello world");
        assertThat(chunks.get(0).sourceId()).isEqualTo("chunk-1");
        assertThat(chunks.get(0).score()).isEqualTo(0.87);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("document_chunks").contains("<=>");

        verify(statement).setInt(3, 5);
        verify(embedder).embed("query text");
    }
}
