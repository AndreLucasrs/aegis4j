package dev.aegis4j.core.skill;

import dev.aegis4j.api.skill.Skill;
import dev.aegis4j.api.skill.SkillDescriptor;

import java.util.List;
import java.util.Optional;

public interface SkillRegistry {

    void register(Skill skill);

    /**
     * Descriptors only (name + description + trigger keywords) — this is all
     * that goes into the system prompt's always-on skill catalog by default.
     */
    List<SkillDescriptor> listDescriptors();

    Optional<Skill> findByName(String name);

    /**
     * Deterministic v1 matching: skills whose trigger keywords appear in the
     * given user input.
     */
    List<Skill> matchByKeywords(String userInput);

    static SkillRegistry inMemory() {
        return new InMemorySkillRegistry();
    }
}
