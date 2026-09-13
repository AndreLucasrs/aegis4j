package dev.aegis4j.core.persona;

import dev.aegis4j.api.persona.Persona;

public final class PersonaManager {

    private volatile Persona active;

    public PersonaManager(Persona initial) {
        this.active = initial;
    }

    public static PersonaManager none() {
        return new PersonaManager(Persona.none());
    }

    public Persona active() {
        return active;
    }

    public void activate(Persona persona) {
        this.active = persona;
    }
}
