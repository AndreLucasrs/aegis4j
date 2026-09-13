package dev.aegis4j.core.guard;

import dev.aegis4j.api.guard.Guard;
import dev.aegis4j.api.guard.GuardContext;
import dev.aegis4j.api.guard.GuardResult;

import java.util.List;

/**
 * Runs an ordered list of {@link Guard}s over a piece of text. A {@code Block}
 * short-circuits the chain by throwing; a {@code Modify} rewrites the text and
 * feeds the rewritten version into the next guard, so guards compose.
 */
public final class GuardChain {

    private final List<Guard> guards;

    public GuardChain(List<Guard> guards) {
        this.guards = List.copyOf(guards);
    }

    public static GuardChain of(Guard... guards) {
        return new GuardChain(List.of(guards));
    }

    public List<Guard> guards() {
        return guards;
    }

    public String runInput(GuardContext ctx, String text) {
        return run(ctx, text, Guard::checkInput);
    }

    public String runOutput(GuardContext ctx, String text) {
        return run(ctx, text, Guard::checkOutput);
    }

    private String run(GuardContext ctx, String text, GuardCheck check) {
        String current = text;
        for (Guard guard : guards) {
            GuardResult result = check.apply(guard, ctx, current);
            if (result instanceof GuardResult.Block block) {
                throw new GuardBlockedException(guard.id(), block);
            }
            if (result instanceof GuardResult.Modify modify) {
                current = modify.sanitizedText();
            }
        }
        return current;
    }

    @FunctionalInterface
    private interface GuardCheck {
        GuardResult apply(Guard guard, GuardContext ctx, String text);
    }
}
