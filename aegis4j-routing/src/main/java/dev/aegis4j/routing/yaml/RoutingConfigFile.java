package dev.aegis4j.routing.yaml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record RoutingConfigFile(List<RoutingRuleEntry> rules, @JsonProperty("default") RouteTargetEntry defaultTarget) {
}
