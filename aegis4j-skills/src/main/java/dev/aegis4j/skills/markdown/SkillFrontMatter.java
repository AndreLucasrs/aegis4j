package dev.aegis4j.skills.markdown;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record SkillFrontMatter(String name, String description, List<String> triggers) {
}
