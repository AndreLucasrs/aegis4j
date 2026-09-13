package dev.aegis4j.api.guard;

/**
 * A deterministic, in-process input/output interceptor. Guards never call an
 * LLM themselves — that is the whole point: a rule that runs in code cannot be
 * talked out of running by a clever prompt. Implementations must be stateless
 * and thread-safe, since a single engine instance serves concurrent requests.
 */
public interface Guard {

    String id();

    GuardResult checkInput(GuardContext ctx, String text);

    GuardResult checkOutput(GuardContext ctx, String text);
}
