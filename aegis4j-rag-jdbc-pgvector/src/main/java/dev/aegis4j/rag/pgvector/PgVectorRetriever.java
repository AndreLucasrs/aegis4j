package dev.aegis4j.rag.pgvector;

import com.pgvector.PGvector;
import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.rag.Retriever;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Direct JDBC (no ORM) cosine/L2/inner-product similarity search against a Postgres+pgvector table. */
public final class PgVectorRetriever implements Retriever {

    private final DataSource dataSource;
    private final Embedder embedder;
    private final PgVectorRetrieverConfig config;

    public PgVectorRetriever(DataSource dataSource, Embedder embedder, PgVectorRetrieverConfig config) {
        this.dataSource = dataSource;
        this.embedder = embedder;
        this.config = config;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        PGvector queryVector = new PGvector(embedder.embed(query));
        try (Connection connection = dataSource.getConnection()) {
            PGvector.addVectorType(connection);
            try (PreparedStatement statement = connection.prepareStatement(buildQuery())) {
                statement.setObject(1, queryVector);
                statement.setObject(2, queryVector);
                statement.setInt(3, topK);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return mapResults(resultSet);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query pgvector retriever against table " + config.table(), e);
        }
    }

    private String buildQuery() {
        return "SELECT " + config.idColumn() + ", " + config.contentColumn() + ", "
                + "1 - (" + config.embeddingColumn() + " " + config.distanceOperator() + " ?) AS score "
                + "FROM " + config.table() + " "
                + "ORDER BY " + config.embeddingColumn() + " " + config.distanceOperator() + " ? "
                + "LIMIT ?";
    }

    private List<RetrievedChunk> mapResults(ResultSet resultSet) throws SQLException {
        List<RetrievedChunk> chunks = new ArrayList<>();
        while (resultSet.next()) {
            chunks.add(new RetrievedChunk(
                    resultSet.getString(config.contentColumn()),
                    resultSet.getString(config.idColumn()),
                    resultSet.getDouble("score"),
                    Map.of()
            ));
        }
        return List.copyOf(chunks);
    }
}
