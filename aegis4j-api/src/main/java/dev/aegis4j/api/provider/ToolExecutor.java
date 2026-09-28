package dev.aegis4j.api.provider;

/**
 * Runs a single {@link ToolCall} the model requested and returns the result
 * to feed back to it. Deliberately caller-supplied: {@code aegis4j-core}
 * never ships a default implementation, since deciding what is safe to
 * execute belongs to whoever embeds the library, not the library itself.
 */
@FunctionalInterface
public interface ToolExecutor {

    String execute(ToolCall call);
}
