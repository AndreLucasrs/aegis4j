package dev.aegis4j.skills.markdown;

import dev.aegis4j.api.skill.Skill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownSkillLoaderTest {

    private final MarkdownSkillLoader loader = new MarkdownSkillLoader();

    @Test
    void parsesDescriptorEagerlyFromFixture() throws IOException {
        Path fixture = Path.of("src/test/resources/skills/weather-explainer.md");
        Skill skill = loader.loadFile(fixture);

        assertThat(skill.descriptor().name()).isEqualTo("weather-explainer");
        assertThat(skill.descriptor().description()).isEqualTo("Explains weather concepts in simple, non-technical terms.");
        assertThat(skill.descriptor().triggerKeywords()).containsExactly("weather", "forecast", "clima");
    }

    @Test
    void bodyIsReadLazilyNotAtLoadTime(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("lazy-skill.md");
        Files.writeString(file, """
                ---
                name: lazy-skill
                description: proves lazy loading
                triggers: [lazy]
                ---
                original body
                """);

        Skill skill = loader.loadFile(file);

        // Rewrite the body on disk AFTER loadFile() returned, before body() is ever called.
        Files.writeString(file, """
                ---
                name: lazy-skill
                description: proves lazy loading
                triggers: [lazy]
                ---
                replaced body
                """);

        assertThat(skill.body()).isEqualTo("replaced body");
    }

    @Test
    void loadsAllMarkdownFilesInADirectory(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("a.md"), """
                ---
                name: skill-a
                description: first
                triggers: []
                ---
                body a
                """);
        Files.writeString(tempDir.resolve("b.md"), """
                ---
                name: skill-b
                description: second
                triggers: []
                ---
                body b
                """);
        Files.writeString(tempDir.resolve("not-a-skill.txt"), "ignored");

        var skills = loader.loadDirectory(tempDir);

        assertThat(skills).hasSize(2);
        assertThat(skills.stream().map(s -> s.descriptor().name())).containsExactlyInAnyOrder("skill-a", "skill-b");
    }

    @Test
    void rejectsFileWithoutFrontMatter(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("broken.md");
        Files.writeString(file, "no frontmatter here\n");

        assertThatThrownBy(() -> loader.loadFile(file)).isInstanceOf(IllegalArgumentException.class);
    }
}
