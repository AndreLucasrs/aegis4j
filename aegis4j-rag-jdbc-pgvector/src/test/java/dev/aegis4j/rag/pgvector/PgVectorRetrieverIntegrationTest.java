package dev.aegis4j.rag.pgvector;

import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.api.rag.RetrievedChunk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres+pgvector, via Testcontainers. Tagged {@code requires-docker}
 * and excluded from the default {@code test} task (see build.gradle.kts) —
 * run explicitly via {@code ./gradlew :aegis4j-rag-jdbc-pgvector:pgVectorIntegrationTest}.
 */
@Tag("requires-docker")
@Testcontainers
class PgVectorRetrieverIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    @BeforeAll
    static void setUpDatabase() throws Exception {
        POSTGRES.start();
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TABLE document_chunks (id text primary key, content text, embedding vector(3))");
            statement.execute("INSERT INTO document_chunks VALUES ('a', 'close to query', '[1,0,0]')");
            statement.execute("INSERT INTO document_chunks VALUES ('b', 'far from query', '[0,0,1]')");
        }
    }

    @AfterAll
    static void tearDownDatabase() {
        POSTGRES.stop();
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    @Test
    void ordersResultsByRealCosineSimilarity() {
        Embedder embedder = new Embedder() {
            @Override
            public float[] embed(String text) {
                return new float[]{1, 0, 0}; // identical to chunk "a"'s embedding
            }

            @Override
            public int dimensions() {
                return 3;
            }
        };
        PgVectorRetriever retriever = new PgVectorRetriever(
                dataSource(), embedder, PgVectorRetrieverConfig.defaults("document_chunks")
        );

        List<RetrievedChunk> results = retriever.retrieve("query", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).sourceId()).isEqualTo("a");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }
}
