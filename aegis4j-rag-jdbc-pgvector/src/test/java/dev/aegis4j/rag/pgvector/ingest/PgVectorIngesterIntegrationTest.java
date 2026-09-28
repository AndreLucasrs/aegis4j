package dev.aegis4j.rag.pgvector.ingest;

import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.rag.pgvector.PgVectorRetriever;
import dev.aegis4j.rag.pgvector.PgVectorRetrieverConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: {@link PgVectorIngester} writes chunks that {@link PgVectorRetriever}
 * can then find, both driven against a real Postgres+pgvector container. Real
 * Postgres, via Testcontainers. Tagged {@code requires-docker} and excluded
 * from the default {@code test} task (see build.gradle.kts) — run explicitly
 * via {@code ./gradlew :aegis4j-rag-jdbc-pgvector:pgVectorIntegrationTest}.
 */
@Tag("requires-docker")
@Testcontainers
class PgVectorIngesterIntegrationTest {

    private static final int DIMENSIONS = 16;
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    @BeforeAll
    static void setUpDatabase() throws Exception {
        POSTGRES.start();
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TABLE document_chunks (id text primary key, content text, embedding vector("
                    + DIMENSIONS + "))");
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
    void ingestedDocumentsAreFoundByTheRetriever(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("postgres.txt"),
                "Postgres is a powerful open source relational database with pgvector support for vector search.");
        Files.writeString(directory.resolve("bananas.txt"),
                "Bananas are a great source of potassium and make a healthy snack.");

        Embedder embedder = new HashEmbedder(DIMENSIONS);
        DataSource dataSource = dataSource();
        PgVectorRetrieverConfig config = PgVectorRetrieverConfig.defaults("document_chunks");

        PgVectorIngester ingester = new PgVectorIngester(dataSource, embedder, config, new FixedSizeChunker(1000, 0));
        int chunkCount = ingester.ingest(new TextDocumentLoader(directory));

        assertThat(chunkCount).isEqualTo(2);

        PgVectorRetriever retriever = new PgVectorRetriever(dataSource, embedder, config);
        List<RetrievedChunk> results = retriever.retrieve("Which database supports vector search?", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).sourceId()).isEqualTo("postgres.txt#0");
        assertThat(results.get(0).content()).contains("Postgres");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }
}
