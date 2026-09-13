package dev.aegis4j.api.skill;

/**
 * A capability that can be offered to the model. Only {@link #descriptor()}
 * (name + description + trigger keywords) is ever cheap to read and is what
 * goes into the system prompt's always-on skill catalog. {@link #body()} is
 * lazy and potentially expensive (a file read, a computed prompt fragment)
 * and must only be invoked once a skill is actually activated for a turn.
 */
public interface Skill {

    SkillDescriptor descriptor();

    String body();
}
