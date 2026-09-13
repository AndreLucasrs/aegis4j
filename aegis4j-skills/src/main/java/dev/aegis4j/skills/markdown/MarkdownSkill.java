package dev.aegis4j.skills.markdown;

import dev.aegis4j.api.skill.Skill;
import dev.aegis4j.api.skill.SkillDescriptor;

import java.util.function.Supplier;

public final class MarkdownSkill implements Skill {

    private final SkillDescriptor descriptor;
    private final Supplier<String> bodySupplier;

    public MarkdownSkill(SkillDescriptor descriptor, Supplier<String> bodySupplier) {
        this.descriptor = descriptor;
        this.bodySupplier = bodySupplier;
    }

    @Override
    public SkillDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public String body() {
        return bodySupplier.get();
    }
}
