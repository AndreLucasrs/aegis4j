package dev.aegis4j.routing.rule;

import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingContext;
import dev.aegis4j.api.routing.RoutingRule;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class KeywordRoutingRule implements RoutingRule {

    private final List<String> keywords;
    private final RouteTarget target;

    public KeywordRoutingRule(List<String> keywords, RouteTarget target) {
        this.keywords = List.copyOf(keywords);
        this.target = target;
    }

    @Override
    public Optional<RouteTarget> match(RoutingContext context) {
        if (context.userInput() == null || context.userInput().isBlank()) {
            return Optional.empty();
        }
        String normalized = context.userInput().toLowerCase(Locale.ROOT);
        boolean matches = keywords.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
        return matches ? Optional.of(target) : Optional.empty();
    }
}
