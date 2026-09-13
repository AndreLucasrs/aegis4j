package dev.aegis4j.api.skill;

import java.util.List;

public record SkillDescriptor(String name, String description, List<String> triggerKeywords) {
}
