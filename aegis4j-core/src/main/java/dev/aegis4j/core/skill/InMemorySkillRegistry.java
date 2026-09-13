package dev.aegis4j.core.skill;

import dev.aegis4j.api.skill.Skill;
import dev.aegis4j.api.skill.SkillDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class InMemorySkillRegistry implements SkillRegistry {

    private final ConcurrentMap<String, Skill> skillsByName = new ConcurrentHashMap<>();

    @Override
    public void register(Skill skill) {
        skillsByName.put(skill.descriptor().name(), skill);
    }

    @Override
    public List<SkillDescriptor> listDescriptors() {
        List<SkillDescriptor> descriptors = new ArrayList<>();
        for (Skill skill : skillsByName.values()) {
            descriptors.add(skill.descriptor());
        }
        return List.copyOf(descriptors);
    }

    @Override
    public Optional<Skill> findByName(String name) {
        return Optional.ofNullable(skillsByName.get(name));
    }

    @Override
    public List<Skill> matchByKeywords(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return List.of();
        }
        String normalizedInput = userInput.toLowerCase(Locale.ROOT);
        List<Skill> matches = new ArrayList<>();
        for (Skill skill : skillsByName.values()) {
            boolean triggered = skill.descriptor().triggerKeywords().stream()
                    .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                    .anyMatch(normalizedInput::contains);
            if (triggered) {
                matches.add(skill);
            }
        }
        return List.copyOf(matches);
    }
}
