package dev.aegis4j.routing.rule;

import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingContext;
import dev.aegis4j.api.routing.RoutingRule;

import java.util.Optional;
import java.util.regex.Pattern;

public final class RegexRoutingRule implements RoutingRule {

    private final Pattern pattern;
    private final RouteTarget target;

    public RegexRoutingRule(Pattern pattern, RouteTarget target) {
        this.pattern = pattern;
        this.target = target;
    }

    @Override
    public Optional<RouteTarget> match(RoutingContext context) {
        if (context.userInput() == null) {
            return Optional.empty();
        }
        return pattern.matcher(context.userInput()).find() ? Optional.of(target) : Optional.empty();
    }
}
