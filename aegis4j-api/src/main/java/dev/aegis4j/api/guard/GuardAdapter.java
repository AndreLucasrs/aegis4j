package dev.aegis4j.api.guard;

/**
 * Convenience base for guards that only care about one direction — override
 * just {@link #checkInput} or just {@link #checkOutput}; the other passes
 * through untouched.
 */
public abstract class GuardAdapter implements Guard {

    @Override
    public GuardResult checkInput(GuardContext ctx, String text) {
        return GuardResult.pass();
    }

    @Override
    public GuardResult checkOutput(GuardContext ctx, String text) {
        return GuardResult.pass();
    }
}
