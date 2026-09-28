package dev.aegis4j.core.engine;

/**
 * Thrown when {@link Aegis4jEngine#chat} keeps receiving tool calls past the
 * configured iteration cap, so a model stuck calling tools forever fails
 * loudly instead of looping the process indefinitely.
 */
public final class ToolCallLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ToolCallLimitExceededException(int maxIterations) {
        super("Exceeded the maximum of " + maxIterations
                + " tool-calling iterations without the model producing a final answer");
    }
}
