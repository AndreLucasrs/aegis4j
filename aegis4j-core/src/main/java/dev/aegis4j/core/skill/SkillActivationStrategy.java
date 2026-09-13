package dev.aegis4j.core.skill;

import dev.aegis4j.api.skill.Skill;

import java.util.List;

/**
 * Decides which skills, beyond the always-on catalog, get their full
 * {@link Skill#body()} injected for a given turn. v1 ships only
 * {@link KeywordSkillActivationStrategy}; a v0.2 tool-call-based strategy
 * (the model itself requests {@code load_skill(name)}) implements this same
 * interface without touching callers.
 */
public interface SkillActivationStrategy {

    List<Skill> activate(SkillRegistry registry, String userInput);
}
