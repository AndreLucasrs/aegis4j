package dev.aegis4j.api.provider;

/**
 * Runs a single {@link ToolCall} the model requested and returns the result
 * to feed back to it. Deliberately caller-supplied: {@code aegis4j-core}
 * never ships a default implementation, since deciding what is safe to
 * execute belongs to whoever embeds the library, not the library itself.
 *
 * <p>If {@link #execute} throws, {@code Aegis4jEngine} catches it and feeds
 * the model only the thrown exception's class name plus a generic message —
 * never {@link Throwable#getMessage()} — since that message may end up
 * inside a request sent to a third-party provider, and exception messages
 * routinely carry things that must not leave the process (stack details,
 * connection strings, internal paths). Implementations that need the real
 * failure detail to reach the model should return it explicitly as the
 * result string rather than relying on the exception path.
 *
 * <p><b>Security:</b> {@code Aegis4jEngine}'s guard chain only sanitizes the
 * user's initial input and the model's final answer. Tool results returned
 * here go straight back into the conversation unsanitized — a tool backed by
 * an external, untrusted system (a web search, a ticket, a document) is a
 * classic prompt-injection vector, and implementations that call out to such
 * systems are responsible for sanitizing what they return.
 */
@FunctionalInterface
public interface ToolExecutor {

    String execute(ToolCall call);
}
