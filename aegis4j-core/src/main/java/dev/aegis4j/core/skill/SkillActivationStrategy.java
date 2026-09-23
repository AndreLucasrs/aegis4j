package dev.aegis4j.core.skill;

import dev.aegis4j.api.skill.Skill;

import java.util.List;

/**
 * Decides which skills get their full {@link Skill#body()} injected for a
 * given turn, and whether the skill catalog (name + description of every
 * registered skill) is worth showing at all. v1 ships only
 * {@link KeywordSkillActivationStrategy}; a v0.2 tool-call-based strategy
 * (the model itself requests {@code load_skill(name)}) implements this same
 * interface without touching callers — and is exactly the kind of strategy
 * that needs {@link #includeCatalogInSystemPrompt()} to stay {@code true},
 * since the model can only request a skill it knows exists.
 */
public interface SkillActivationStrategy {

    List<Skill> activate(SkillRegistry registry, String userInput);

    /**
     * Whether {@link dev.aegis4j.core.prompt.PromptAssembler} should list
     * every registered skill's name + description in the system prompt on
     * every turn, regardless of what {@link #activate} injects in full for
     * this specific turn. Defaults to {@code true} to preserve existing
     * behavior for strategies that predate this method. A silent,
     * deterministic strategy (e.g. keyword matching with no model-facing
     * "menu" of skills to choose from) is a reasonable candidate to override
     * this to {@code false}, since the catalog serves it no functional
     * purpose beyond token cost and tangent risk on small models.
     */
    default boolean includeCatalogInSystemPrompt() {
        return true;
    }
}
