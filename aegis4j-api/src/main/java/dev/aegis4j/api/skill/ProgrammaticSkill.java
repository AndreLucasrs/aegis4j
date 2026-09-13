package dev.aegis4j.api.skill;

/**
 * Base class for skills whose body is computed rather than read from a file —
 * e.g. it calls another service or formats dynamic content. Registers into the
 * same {@code SkillRegistry} catalog as declarative (Markdown) skills.
 */
public abstract class ProgrammaticSkill implements Skill {

    private final SkillDescriptor descriptor;

    protected ProgrammaticSkill(SkillDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public final SkillDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public abstract String body();
}
