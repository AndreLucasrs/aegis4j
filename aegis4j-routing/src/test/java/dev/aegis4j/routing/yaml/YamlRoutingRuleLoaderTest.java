package dev.aegis4j.routing.yaml;

import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingContext;
import dev.aegis4j.api.routing.RoutingRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlRoutingRuleLoaderTest {

    private final YamlRoutingRuleLoader loader = new YamlRoutingRuleLoader();

    @Test
    void loadsRulesAndDefaultFromFixture() {
        Path fixture = Path.of("src/test/resources/routing/routing.yaml");

        List<RoutingRule> rules = loader.loadFile(fixture);
        RouteTarget defaultTarget = loader.loadDefaultTarget(fixture);

        assertThat(rules).hasSize(2);
        assertThat(defaultTarget).isEqualTo(new RouteTarget("ollama", "llama3.1:8b"));

        RoutingContext codeContext = new RoutingContext("please fix this bug", List.of(), Map.of());
        assertThat(rules.get(0).match(codeContext)).isPresent();

        RoutingContext sqlContext = new RoutingContext("help me write a SQL query", List.of(), Map.of());
        assertThat(rules.get(1).match(sqlContext)).isPresent();
    }

    @Test
    void rejectsMissingDefaultBlock(@TempDir Path tempDir) throws IOException {
        Path file = writeYaml(tempDir, """
                rules: []
                """);

        assertThatThrownBy(() -> loader.loadDefaultTarget(file)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMatchWithBothKeywordAndRegex(@TempDir Path tempDir) throws IOException {
        Path file = writeYaml(tempDir, """
                rules:
                  - match:
                      keyword: [a]
                      regex: "b"
                    provider: ollama
                    model: m
                default:
                  provider: ollama
                  model: m
                """);

        assertThatThrownBy(() -> loader.loadFile(file)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMatchWithNeitherKeywordNorRegex(@TempDir Path tempDir) throws IOException {
        Path file = writeYaml(tempDir, """
                rules:
                  - match: {}
                    provider: ollama
                    model: m
                default:
                  provider: ollama
                  model: m
                """);

        assertThatThrownBy(() -> loader.loadFile(file)).isInstanceOf(IllegalArgumentException.class);
    }

    private Path writeYaml(Path tempDir, String content) throws IOException {
        Path file = tempDir.resolve("routing.yaml");
        Files.writeString(file, content);
        return file;
    }
}
