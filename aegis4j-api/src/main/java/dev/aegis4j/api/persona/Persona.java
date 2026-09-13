package dev.aegis4j.api.persona;

import java.util.Map;

public record Persona(String name, String systemPromptPrefix, Map<String, String> styleDirectives) {

    public static Persona none() {
        return new Persona("default", "", Map.of());
    }
}
