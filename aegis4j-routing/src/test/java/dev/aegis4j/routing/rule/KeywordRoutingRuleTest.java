package dev.aegis4j.routing.rule;

import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordRoutingRuleTest {

    private final RouteTarget target = new RouteTarget("ollama", "qwen2.5-coder:7b");
    private final KeywordRoutingRule rule = new KeywordRoutingRule(List.of("code", "bug"), target);

    @Test
    void matchesWhenKeywordPresent() {
        RoutingContext ctx = new RoutingContext("there is a BUG in my code", List.of(), Map.of());
        assertThat(rule.match(ctx)).contains(target);
    }

    @Test
    void doesNotMatchWhenKeywordAbsent() {
        RoutingContext ctx = new RoutingContext("what's the weather like", List.of(), Map.of());
        assertThat(rule.match(ctx)).isEmpty();
    }
}
