package dev.aegis4j.core.routing;

import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingContext;
import dev.aegis4j.api.routing.RoutingRule;

import java.util.List;
import java.util.Optional;

/**
 * Evaluated on every {@code Aegis4jEngine.chat}/{@code chatStream} call when
 * {@code providerId}/{@code model} are absent from the request — same
 * "first rule that matches wins" semantics as {@code GuardChain}.
 */
public final class ModelRouter {

    private final List<RoutingRule> rules;
    private final RouteTarget defaultTarget;

    public ModelRouter(List<RoutingRule> rules, RouteTarget defaultTarget) {
        this.rules = List.copyOf(rules);
        this.defaultTarget = defaultTarget;
    }

    public RouteTarget resolve(RoutingContext context) {
        for (RoutingRule rule : rules) {
            Optional<RouteTarget> match = rule.match(context);
            if (match.isPresent()) {
                return match.get();
            }
        }
        return defaultTarget;
    }

    public List<RoutingRule> rules() {
        return rules;
    }
}
