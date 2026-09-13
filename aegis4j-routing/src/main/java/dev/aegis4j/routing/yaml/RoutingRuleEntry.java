package dev.aegis4j.routing.yaml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record RoutingRuleEntry(MatchEntry match, String provider, String model) {
}
