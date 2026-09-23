package dev.aegis4j.core.prompt;

import dev.aegis4j.api.persona.Persona;
import dev.aegis4j.api.provider.Message;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.skill.Skill;
import dev.aegis4j.api.skill.SkillDescriptor;
import dev.aegis4j.core.skill.SkillActivationStrategy;
import dev.aegis4j.core.skill.SkillRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the final message list sent to a provider: persona prefix, then the
 * skill catalog (name + description only, when {@code includeSkillCatalog}
 * says so), then the full body of any skill activated for this specific
 * turn, then any retrieved RAG context, then conversation history, then the
 * user's turn. Activated skill bodies and retrieved chunks are appended
 * fresh every call and are never cached back into the catalog. Pure/no I/O —
 * the engine resolves {@code retrievedChunks} and {@code includeSkillCatalog}
 * before calling this.
 */
public final class PromptAssembler {

    public List<Message> assemble(
            Persona persona,
            SkillRegistry skillRegistry,
            SkillActivationStrategy activationStrategy,
            boolean includeSkillCatalog,
            List<RetrievedChunk> retrievedChunks,
            List<Message> history,
            String userInput
    ) {
        List<Message> messages = new ArrayList<>();

        String systemPrompt = buildSystemPrompt(persona, skillRegistry, includeSkillCatalog);
        if (!systemPrompt.isBlank()) {
            messages.add(Message.system(systemPrompt));
        }

        for (Skill activated : activationStrategy.activate(skillRegistry, userInput)) {
            messages.add(Message.system("[Skill: " + activated.descriptor().name() + "]\n" + activated.body()));
        }

        if (!retrievedChunks.isEmpty()) {
            messages.add(Message.system(buildContextBlock(retrievedChunks)));
        }

        messages.addAll(history);
        messages.add(Message.user(userInput));
        return List.copyOf(messages);
    }

    private String buildContextBlock(List<RetrievedChunk> retrievedChunks) {
        StringBuilder context = new StringBuilder("Context:\n");
        for (RetrievedChunk chunk : retrievedChunks) {
            context.append("- [").append(chunk.sourceId()).append("] ").append(chunk.content()).append('\n');
        }
        return context.toString().stripTrailing();
    }

    private String buildSystemPrompt(Persona persona, SkillRegistry skillRegistry, boolean includeSkillCatalog) {
        StringBuilder sb = new StringBuilder();
        if (persona != null && !persona.systemPromptPrefix().isBlank()) {
            sb.append(persona.systemPromptPrefix()).append("\n\n");
        }

        if (includeSkillCatalog) {
            List<SkillDescriptor> descriptors = skillRegistry.listDescriptors();
            if (!descriptors.isEmpty()) {
                sb.append("Available skills:\n");
                for (SkillDescriptor descriptor : descriptors) {
                    sb.append("- ").append(descriptor.name()).append(": ").append(descriptor.description()).append('\n');
                }
            }
        }

        return sb.toString().stripTrailing();
    }
}
