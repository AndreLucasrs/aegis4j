package dev.aegis4j.server;

import dev.aegis4j.api.guard.Guard;
import dev.aegis4j.guardrails.builtin.EncodedInjectionGuard;
import dev.aegis4j.guardrails.builtin.PromptInjectionGuard;

import java.util.List;
import java.util.Locale;

/**
 * How the sidecar applies the prompt-injection guards, selected by the
 * {@code AEGIS4J_INJECTION_GUARDS} environment variable. On by default: the
 * server is the entry point for arbitrary callers, so shipping it with no
 * injection screening at all would be the unsafe default.
 */
public enum InjectionGuardMode {

    /** {@link PromptInjectionGuard} + {@link EncodedInjectionGuard} with default patterns (default). */
    ON,
    /** {@code ON}, but {@link EncodedInjectionGuard#strict()}: any decodable text payload is blocked. */
    STRICT,
    /** No injection guards (opt-out). */
    OFF;

    public static final String ENV_VAR = "AEGIS4J_INJECTION_GUARDS";

    /**
     * @param value raw env value; {@code null}/blank means the default ({@link #ON})
     * @throws IllegalArgumentException for anything unrecognized — a typo like {@code "disabled"} must not
     *         silently leave the guards in an unintended state
     */
    public static InjectionGuardMode parse(String value) {
        if (value == null || value.isBlank()) {
            return ON;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1" -> ON;
            case "strict" -> STRICT;
            case "off", "false", "0" -> OFF;
            default -> throw new IllegalArgumentException(
                    "Invalid " + ENV_VAR + " value: '" + value + "' (expected on, strict or off)");
        };
    }

    /** The guards for this mode, in chain order; empty for {@link #OFF}. */
    public List<Guard> guards() {
        return switch (this) {
            case ON -> List.of(PromptInjectionGuard.defaultPatterns(), EncodedInjectionGuard.defaultPatterns());
            case STRICT -> List.of(PromptInjectionGuard.defaultPatterns(), EncodedInjectionGuard.strict());
            case OFF -> List.of();
        };
    }
}
