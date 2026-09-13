package dev.aegis4j.api.routing;

import java.util.Optional;

/** Evaluated in order by {@code ModelRouter} — first rule that matches wins, mirroring {@code GuardChain}. */
public interface RoutingRule {

    Optional<RouteTarget> match(RoutingContext context);
}
