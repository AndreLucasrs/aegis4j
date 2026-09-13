package dev.aegis4j.routing.rule;

import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RegexRoutingRuleTest {

    private final RouteTarget target = new RouteTarget("ollama", "qwen2.5-coder:7b");
    private final RegexRoutingRule rule = new RegexRoutingRule(Pattern.compile("(?i)\\b(sql|database)\\b"), target);

    @Test
    void matchesWhenPatternFound() {
        RoutingContext ctx = new RoutingContext("how do I write this SQL query", List.of(), Map.of());
        assertThat(rule.match(ctx)).contains(target);
    }

    @Test
    void doesNotMatchWhenPatternAbsent() {
        RoutingContext ctx = new RoutingContext("what's the weather like", List.of(), Map.of());
        assertThat(rule.match(ctx)).isEmpty();
    }
}
