package dev.aegis4j.routing.yaml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record MatchEntry(List<String> keyword, String regex) {
}
