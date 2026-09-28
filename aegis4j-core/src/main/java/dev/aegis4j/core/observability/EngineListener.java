package dev.aegis4j.core.observability;

import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.rag.RetrievedChunk;

import java.time.Duration;
import java.util.List;

/**
 * Opt-in hook into the {@code Aegis4jEngine} request pipeline, for tracing
 * and metrics integrations (see {@code aegis4j-observability-otel} for an
 * OpenTelemetry-backed implementation). Every method is a no-op default so
 * an implementation only overrides the phases it cares about.
 *
 * <p>{@code requestId} is the correlation key across a single request's
 * callbacks (it matches {@link dev.aegis4j.core.engine.ChatRequest#requestId()}).
 * The engine calls these methods on the same thread that runs the pipeline,
 * synchronously and in pipeline order, so a stateful implementation may
 * correlate a request's callbacks via a thread-local rather than tracking
 * {@code requestId} itself.
 *
 * <p>A listener must never let an exception escape: the engine isolates and
 * discards (logging only) whatever a listener throws, so a broken listener
 * can never affect the real response. Implementations must still be
 * thread-safe, since a single engine instance serves concurrent requests.
 */
public interface EngineListener {

    /** Fired once, before the input guard chain runs. */
    default void onChatStarted(String requestId) {
    }

    /** Fired after the input guard chain has run, with the sanitized input it produced. */
    default void onInputGuardComplete(String requestId, String sanitizedInput) {
    }

    /**
     * Fired after retrieval, with whatever chunks were retrieved. Not fired
     * at all when no {@code Retriever} is configured on the engine.
     */
    default void onRetrievalComplete(String requestId, List<RetrievedChunk> chunks) {
    }

    /** Fired once providerId/model are known, whether explicit on the request or resolved via routing. */
    default void onRouteResolved(String requestId, String providerId, String model) {
    }

    /** Fired after the provider call returns, with how long it took. */
    default void onProviderCallComplete(String requestId, CompletionResponse response, Duration duration) {
    }

    /**
     * Fired after the output guard chain has run, with the sanitized output
     * it produced. Not fired by {@code chatStream}, which does not run
     * output guards.
     */
    default void onOutputGuardComplete(String requestId, String sanitizedOutput) {
    }

    /** Fired once the whole pipeline has completed successfully. */
    default void onChatComplete(String requestId, Duration totalDuration) {
    }

    /**
     * Fired instead of {@link #onChatComplete} when the pipeline raises —
     * e.g. a {@code GuardBlockedException} or a provider failure.
     */
    default void onChatFailed(String requestId, Throwable error, Duration totalDuration) {
    }
}
