package dev.aegis4j.rag.pgvector.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reads whole files matching an extension from a directory (non-recursive), one document per file. */
public final class TextDocumentLoader implements DocumentLoader {

    private static final Logger LOGGER = Logger.getLogger(TextDocumentLoader.class.getName());

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
                // Isolated per file: one unreadable entry (permissions, a directory that
                // happens to match the glob, ...) must not discard every other document
                // already read successfully from this directory.
                try {
                    String content = Files.readString(file);
                    documents.add(new Document(file.getFileName().toString(), content, Map.of("path", file.toString())));
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Skipping unreadable file " + file, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list documents in " + directory, e);
        }
        return List.copyOf(documents);
    }
}
