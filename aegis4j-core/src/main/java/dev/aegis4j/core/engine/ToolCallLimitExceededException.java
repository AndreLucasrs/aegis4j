package dev.aegis4j.core.engine;

/**
 * Thrown when {@link Aegis4jEngine#chat} keeps receiving tool calls past the
 * configured iteration cap, so a model stuck calling tools forever fails
 * loudly instead of looping the process indefinitely.
 */
public final class ToolCallLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final String CODE = "tool_call_limit_exceeded";

    public ToolCallLimitExceededException(int maxIterations) {
        super("Exceeded the maximum of " + maxIterations
                + " tool-calling iterations without the model producing a final answer");
    }

    /** Stable machine-readable code, mirroring {@code GuardBlockedException.reasonCode()}, for API layers that map exceptions to a {@code {code, message}} response. */
    public String code() {
        return CODE;
    }
}
