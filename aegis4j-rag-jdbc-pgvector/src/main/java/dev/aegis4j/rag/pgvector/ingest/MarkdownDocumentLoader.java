package dev.aegis4j.rag.pgvector.ingest;

import java.nio.file.Path;
import java.util.List;

/**
 * Reads {@code .md} files from a directory as documents. Content is loaded
 * verbatim (raw Markdown, unparsed) — stripping formatting or splitting on
 * headings is left to a future {@link Chunker}, not this loader.
 */
public final class MarkdownDocumentLoader implements DocumentLoader {

    private final TextDocumentLoader delegate;

    public MarkdownDocumentLoader(Path directory) {
        this.delegate = new TextDocumentLoader(directory, ".md");
    }

    @Override
    public List<Document> load() {
        return delegate.load();
    }
}
