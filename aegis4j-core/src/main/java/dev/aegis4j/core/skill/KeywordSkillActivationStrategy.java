package dev.aegis4j.core.skill;

import dev.aegis4j.api.skill.Skill;

import java.util.List;

public final class KeywordSkillActivationStrategy implements SkillActivationStrategy {

    @Override
    public List<Skill> activate(SkillRegistry registry, String userInput) {
        return registry.matchByKeywords(userInput);
    }
}
