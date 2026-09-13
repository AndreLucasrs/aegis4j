package dev.aegis4j.core.routing;

import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingContext;
import dev.aegis4j.api.routing.RoutingRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRouterTest {

    private final RouteTarget codeTarget = new RouteTarget("ollama", "qwen2.5-coder:7b");
    private final RouteTarget defaultTarget = new RouteTarget("ollama", "llama3.1:8b");

    @Test
    void firstMatchingRuleWins() {
        RoutingRule alwaysMatchesCode = ctx -> Optional.of(codeTarget);
        RoutingRule alwaysMatchesDefault = ctx -> Optional.of(defaultTarget);
        ModelRouter router = new ModelRouter(List.of(alwaysMatchesCode, alwaysMatchesDefault), defaultTarget);

        RouteTarget result = router.resolve(new RoutingContext("anything", List.of(), Map.of()));

        assertThat(result).isEqualTo(codeTarget);
    }

    @Test
    void fallsBackToDefaultWhenNoRuleMatches() {
        RoutingRule neverMatches = ctx -> Optional.empty();
        ModelRouter router = new ModelRouter(List.of(neverMatches), defaultTarget);

        RouteTarget result = router.resolve(new RoutingContext("anything", List.of(), Map.of()));

        assertThat(result).isEqualTo(defaultTarget);
    }
}
