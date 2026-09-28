package dev.aegis4j.rag.pgvector.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads whole files matching an extension from a directory (non-recursive), one document per file. */
public final class TextDocumentLoader implements DocumentLoader {

    private final Path directory;
    private final String extension;

    public TextDocumentLoader(Path directory) {
        this(directory, ".txt");
    }

    TextDocumentLoader(Path directory, String extension) {
        this.directory = directory;
        this.extension = extension;
    }

    @Override
    public List<Document> load() {
        List<Document> documents = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + extension)) {
            for (Path file : files) {
                String content = Files.readString(file);
                documents.add(new Document(file.getFileName().toString(), content, Map.of("path", file.toString())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load documents from " + directory, e);
        }
        return List.copyOf(documents);
    }
}
