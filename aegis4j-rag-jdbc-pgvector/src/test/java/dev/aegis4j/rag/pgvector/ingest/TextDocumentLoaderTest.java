package dev.aegis4j.rag.pgvector.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextDocumentLoaderTest {

    @Test
    void loadsOnlyMatchingFilesFromDirectory(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("a.txt"), "content a");
        Files.writeString(directory.resolve("b.txt"), "content b");
        Files.writeString(directory.resolve("ignored.md"), "should not be loaded");

        List<Document> documents = new TextDocumentLoader(directory).load();

        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(Document::id).containsExactlyInAnyOrder("a.txt", "b.txt");
        assertThat(documents).extracting(Document::content).containsExactlyInAnyOrder("content a", "content b");
    }

    @Test
    void loadsMarkdownFilesVerbatimViaMarkdownDocumentLoader(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("doc.md"), "# Heading\n\nBody text.");
        Files.writeString(directory.resolve("ignored.txt"), "should not be loaded");

        List<Document> documents = new MarkdownDocumentLoader(directory).load();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).id()).isEqualTo("doc.md");
        assertThat(documents.get(0).content()).isEqualTo("# Heading\n\nBody text.");
    }

    @Test
    void returnsEmptyListForDirectoryWithNoMatchingFiles(@TempDir Path directory) {
        List<Document> documents = new TextDocumentLoader(directory).load();

        assertThat(documents).isEmpty();
    }

    @Test
    void skipsAnUnreadableEntryInsteadOfDiscardingTheWholeDirectory(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("good.txt"), "readable content");
        // A directory happens to match the "*.txt" glob; Files.readString on it throws.
        // Loading must isolate that failure instead of losing "good.txt" too.
        Files.createDirectory(directory.resolve("broken.txt"));

        List<Document> documents = new TextDocumentLoader(directory).load();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).id()).isEqualTo("good.txt");
        assertThat(documents.get(0).content()).isEqualTo("readable content");
    }

    @Test
    void matchesTheExtensionCaseInsensitively(@TempDir Path directory) throws IOException {
        // Linux (the typical production filesystem) is case-sensitive, so README.MD would
        // otherwise be silently skipped by a ".md" glob with no log or error.
        Files.writeString(directory.resolve("README.MD"), "upper case extension");
        Files.writeString(directory.resolve("notes.Md"), "mixed case extension");

        List<Document> documents = new MarkdownDocumentLoader(directory).load();

        assertThat(documents).extracting(Document::id).containsExactlyInAnyOrder("README.MD", "notes.Md");
    }

    @Test
    void loadsFilesInSortedOrderForDeterministicIngestion(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("c.txt"), "c");
        Files.writeString(directory.resolve("a.txt"), "a");
        Files.writeString(directory.resolve("b.txt"), "b");

        List<Document> documents = new TextDocumentLoader(directory).load();

        assertThat(documents).extracting(Document::id).containsExactly("a.txt", "b.txt", "c.txt");
    }
}
