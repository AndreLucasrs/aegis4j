package dev.aegis4j.guardrails.builtin;

import dev.aegis4j.api.guard.GuardAdapter;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;

/**
 * Cheapest guard in the built-in set — a hard character ceiling on input
 * and/or output, primarily as cost/DoS protection. Stateless.
 */
public final class MaxLengthGuard extends GuardAdapter {

    private final int maxInputChars;
    private final int maxOutputChars;

    public MaxLengthGuard(int maxInputChars, int maxOutputChars) {
        this.maxInputChars = maxInputChars;
        this.maxOutputChars = maxOutputChars;
    }

    public static MaxLengthGuard forInput(int maxChars) {
        return new MaxLengthGuard(maxChars, Integer.MAX_VALUE);
    }

    public static MaxLengthGuard forOutput(int maxChars) {
        return new MaxLengthGuard(Integer.MAX_VALUE, maxChars);
    }

    @Override
    public String id() {
        return "max-length";
    }

    @Override
    public GuardResult checkInput(GuardContext ctx, String text) {
        if (text.length() > maxInputChars) {
            return GuardResult.block("input-too-long",
                    "Input length " + text.length() + " exceeds max of " + maxInputChars + " characters");
        }
        return GuardResult.pass();
    }

    @Override
    public GuardResult checkOutput(GuardContext ctx, String text) {
        if (text.length() > maxOutputChars) {
            return GuardResult.block("output-too-long",
                    "Output length " + text.length() + " exceeds max of " + maxOutputChars + " characters");
        }
        return GuardResult.pass();
    }
}
