package dev.aegis4j.skills.markdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.aegis4j.api.skill.Skill;
import dev.aegis4j.api.skill.SkillDescriptor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads declarative skills from {@code .md} files with a YAML frontmatter
 * block:
 *
 * <pre>{@code
 * ---
 * name: weather-explainer
 * description: Explains weather concepts in simple, non-technical terms.
 * triggers: [weather, forecast, clima]
 * ---
 * Full instructions body goes here...
 * }</pre>
 *
 * The frontmatter is parsed eagerly into a cheap {@link SkillDescriptor}; the
 * body past the closing {@code ---} is read from disk lazily, only the first
 * time {@link Skill#body()} is called — this is what actually enforces
 * progressive disclosure rather than just choosing not to print it.
 */
public final class MarkdownSkillLoader {

    private static final String DELIMITER = "---";

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public List<Skill> loadDirectory(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            List<Skill> skills = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                skills.add(loadFile(file));
            }
            return List.copyOf(skills);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list skill directory " + dir, e);
        }
    }

    public Skill loadFile(Path file) {
        String frontMatterYaml = readFrontMatterBlock(file);
        SkillFrontMatter frontMatter = parseFrontMatter(file, frontMatterYaml);

        SkillDescriptor descriptor = new SkillDescriptor(
                frontMatter.name(),
                frontMatter.description(),
                frontMatter.triggers() == null ? List.of() : frontMatter.triggers()
        );

        return new MarkdownSkill(descriptor, () -> readBody(file));
    }

    private String readFrontMatterBlock(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String firstLine = reader.readLine();
            if (firstLine == null || !firstLine.strip().equals(DELIMITER)) {
                throw new IllegalArgumentException("Skill file " + file + " must start with a '---' frontmatter block");
            }
            StringBuilder frontMatter = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.strip().equals(DELIMITER)) {
                    return frontMatter.toString();
                }
                frontMatter.append(line).append('\n');
            }
            throw new IllegalArgumentException("Skill file " + file + " frontmatter block was never closed with '---'");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read skill frontmatter from " + file, e);
        }
    }

    private SkillFrontMatter parseFrontMatter(Path file, String yaml) {
        try {
            return yamlMapper.readValue(yaml, SkillFrontMatter.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse frontmatter YAML in " + file, e);
        }
    }

    private String readBody(Path file) {
        try {
            String content = Files.readString(file);
            int firstDelimiterEnd = content.indexOf('\n');
            int secondDelimiter = content.indexOf("\n" + DELIMITER, firstDelimiterEnd);
            if (secondDelimiter < 0) {
                throw new IllegalArgumentException("Skill file " + file + " frontmatter block was never closed with '---'");
            }
            int bodyStart = content.indexOf('\n', secondDelimiter + 1);
            return bodyStart < 0 ? "" : content.substring(bodyStart + 1).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read skill body from " + file, e);
        }
    }
}
