package dev.aegis4j.api.guard;

public sealed interface GuardResult {

    record Pass() implements GuardResult {
    }

    record Block(String reasonCode, String message) implements GuardResult {
    }

    record Modify(String sanitizedText, String reasonCode) implements GuardResult {
    }

    Pass PASS = new Pass();

    static GuardResult pass() {
        return PASS;
    }

    static GuardResult block(String reasonCode, String message) {
        return new Block(reasonCode, message);
    }

    static GuardResult modify(String sanitizedText, String reasonCode) {
        return new Modify(sanitizedText, reasonCode);
    }
}
