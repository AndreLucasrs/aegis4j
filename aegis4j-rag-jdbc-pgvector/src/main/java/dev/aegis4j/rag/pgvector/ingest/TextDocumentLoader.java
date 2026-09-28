package dev.aegis4j.rag.pgvector.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Reads whole files matching an extension from a directory (non-recursive), one document per
 * file. The extension match is case-insensitive (a production filesystem is typically Linux,
 * where {@code README.MD} is a distinct, easy-to-miss name from {@code readme.md}), and files
 * are processed in a fixed (sorted by path) order so ingestion is deterministic across runs and
 * environments, matching the convention {@code MarkdownSkillLoader} (aegis4j-skills) already
 * established for this kind of directory scan.
 */
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
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(this::matchesExtension).sorted().toList()) {
                // Isolated per file: one unreadable entry (permissions, a directory that
                // happens to match the extension, ...) must not discard every other document
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

    private boolean matchesExtension(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT));
    }
}
