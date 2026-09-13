package dev.aegis4j.routing.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingRule;
import dev.aegis4j.routing.rule.KeywordRoutingRule;
import dev.aegis4j.routing.rule.RegexRoutingRule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Loads routing rules from a YAML file (same eager-parse, fail-fast-on-malformed
 * style as {@code MarkdownSkillLoader}):
 *
 * <pre>{@code
 * rules:
 *   - match: {keyword: [code, bug, refactor]}
 *     provider: ollama
 *     model: qwen2.5-coder:7b
 *   - match: {regex: "(?i)\\b(sql|database)\\b"}
 *     provider: ollama
 *     model: qwen2.5-coder:7b
 * default:
 *   provider: ollama
 *   model: llama3.1:8b
 * }</pre>
 */
public final class YamlRoutingRuleLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public List<RoutingRule> loadFile(Path routingYaml) {
        return toRules(parse(routingYaml).rules());
    }

    public RouteTarget loadDefaultTarget(Path routingYaml) {
        RoutingConfigFile config = parse(routingYaml);
        if (config.defaultTarget() == null) {
            throw new IllegalArgumentException(
                    "Routing config " + routingYaml + " is missing a required top-level 'default' block");
        }
        return new RouteTarget(config.defaultTarget().provider(), config.defaultTarget().model());
    }

    private RoutingConfigFile parse(Path routingYaml) {
        try {
            return yamlMapper.readValue(Files.readString(routingYaml), RoutingConfigFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read routing config " + routingYaml, e);
        }
    }

    private List<RoutingRule> toRules(List<RoutingRuleEntry> entries) {
        if (entries == null) {
            return List.of();
        }
        List<RoutingRule> rules = new ArrayList<>();
        for (RoutingRuleEntry entry : entries) {
            rules.add(toRule(entry));
        }
        return List.copyOf(rules);
    }

    private RoutingRule toRule(RoutingRuleEntry entry) {
        if (entry.match() == null) {
            throw new IllegalArgumentException("Routing rule for provider '" + entry.provider() + "' is missing a 'match' block");
        }
        boolean hasKeyword = entry.match().keyword() != null && !entry.match().keyword().isEmpty();
        boolean hasRegex = entry.match().regex() != null && !entry.match().regex().isBlank();
        if (hasKeyword == hasRegex) {
            throw new IllegalArgumentException(
                    "Routing rule match block must have exactly one of 'keyword' or 'regex', got: " + entry.match());
        }
        RouteTarget target = new RouteTarget(entry.provider(), entry.model());
        return hasKeyword
                ? new KeywordRoutingRule(entry.match().keyword(), target)
                : new RegexRoutingRule(Pattern.compile(entry.match().regex()), target);
    }
}
