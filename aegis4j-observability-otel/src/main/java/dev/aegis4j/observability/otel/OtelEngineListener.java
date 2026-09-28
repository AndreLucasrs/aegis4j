package dev.aegis4j.observability.otel;

import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.core.observability.EngineListener;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link EngineListener} that creates one OpenTelemetry span per
 * {@code Aegis4jEngine.chat()}/{@code chatStream()} call, filling in
 * attributes as each pipeline phase completes and ending the span (with an
 * OK or ERROR {@link StatusCode}) when {@code onChatComplete}/
 * {@code onChatFailed} fires.
 *
 * <p>Correlates a request's callbacks via OpenTelemetry's own {@code
 * Context} — {@link Span#current()}, backed by {@link Span#makeCurrent()} —
 * rather than remembering the span itself. That matters under reentrancy:
 * nothing in the {@code Guard}/{@code Provider}/{@code Retriever}/
 * {@code ModelRouter} contracts rules out a nested {@code chat()}/
 * {@code chatStream()} call on the same thread (e.g. a custom component
 * that itself drives another engine call from inside one of those phases).
 * A single {@code ThreadLocal<Span>} would break under that: the inner
 * call's {@code onChatStarted} would overwrite it, and the inner call's
 * {@code onChatComplete}/{@code onChatFailed} would clear it — leaving the
 * outer call's later callbacks with no span to attach to. {@code Context}'s
 * make-current/close pairs are a proper LIFO stack instead, so the inner
 * span is current for its own callbacks (and is even correctly parented
 * under the outer span) and the outer span is automatically current again
 * once the inner call's scope closes.
 *
 * <p>A small per-thread {@link Deque} of {@link Scope} is still kept —
 * not for correlation, only because the OpenTelemetry API requires holding
 * on to the exact {@code Scope} instance returned by {@code makeCurrent()}
 * in order to close it, and closing must happen in the same LIFO order the
 * scopes were opened for the {@code Context} stack to unwind correctly.
 *
 * <p>Never throws: every method either finds a currently active span (made
 * current by {@code onChatStarted}) or silently no-ops, so a bug here can
 * never propagate into the pipeline — though {@code Aegis4jEngine} isolates
 * listener exceptions regardless.
 */
public final class OtelEngineListener implements EngineListener {

    static final String INSTRUMENTATION_NAME = "dev.aegis4j";
    static final String SPAN_NAME = "aegis4j.chat";

    static final AttributeKey<String> ATTR_REQUEST_ID = AttributeKey.stringKey("aegis4j.request_id");
    static final AttributeKey<String> ATTR_PROVIDER_ID = AttributeKey.stringKey("aegis4j.provider_id");
    static final AttributeKey<String> ATTR_MODEL = AttributeKey.stringKey("aegis4j.model");
    static final AttributeKey<Long> ATTR_RETRIEVED_CHUNK_COUNT = AttributeKey.longKey("aegis4j.retrieved_chunk_count");
    static final AttributeKey<Long> ATTR_PROVIDER_CALL_DURATION_MS =
            AttributeKey.longKey("aegis4j.provider_call.duration_ms");
    static final AttributeKey<String> ATTR_FINISH_REASON = AttributeKey.stringKey("aegis4j.finish_reason");
    static final AttributeKey<Long> ATTR_PROMPT_TOKENS = AttributeKey.longKey("aegis4j.usage.prompt_tokens");
    static final AttributeKey<Long> ATTR_COMPLETION_TOKENS = AttributeKey.longKey("aegis4j.usage.completion_tokens");

    private final Tracer tracer;
    private final ThreadLocal<Deque<Scope>> scopeStack = ThreadLocal.withInitial(ArrayDeque::new);

    public OtelEngineListener(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @Override
    public void onChatStarted(String requestId) {
        Span span = tracer.spanBuilder(SPAN_NAME)
                .setAttribute(ATTR_REQUEST_ID, requestId)
                .startSpan();
        scopeStack.get().push(span.makeCurrent());
    }

    @Override
    public void onInputGuardComplete(String requestId, String sanitizedInput) {
        withSpan(span -> span.addEvent("input_guard.complete"));
    }

    @Override
    public void onRetrievalComplete(String requestId, List<RetrievedChunk> chunks) {
        withSpan(span -> span
                .setAttribute(ATTR_RETRIEVED_CHUNK_COUNT, (long) chunks.size())
                .addEvent("retrieval.complete"));
    }

    @Override
    public void onRouteResolved(String requestId, String providerId, String model) {
        withSpan(span -> span
                .setAttribute(ATTR_PROVIDER_ID, providerId)
                .setAttribute(ATTR_MODEL, model)
                .addEvent("route.resolved"));
    }

    @Override
    public void onProviderCallComplete(String requestId, CompletionResponse response, Duration duration) {
        withSpan(span -> {
            span.setAttribute(ATTR_PROVIDER_CALL_DURATION_MS, duration.toMillis());
            if (response.finishReason() != null) {
                span.setAttribute(ATTR_FINISH_REASON, response.finishReason().name());
            }
            if (response.usage() != null) {
                span.setAttribute(ATTR_PROMPT_TOKENS, (long) response.usage().promptTokens());
                span.setAttribute(ATTR_COMPLETION_TOKENS, (long) response.usage().completionTokens());
            }
            span.addEvent("provider_call.complete");
        });
    }

    @Override
    public void onOutputGuardComplete(String requestId, String sanitizedOutput) {
        withSpan(span -> span.addEvent("output_guard.complete"));
    }

    @Override
    public void onChatComplete(String requestId, Duration totalDuration) {
        end(span -> span.setStatus(StatusCode.OK));
    }

    @Override
    public void onChatFailed(String requestId, Throwable error, Duration totalDuration) {
        end(span -> {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error.getMessage() != null ? error.getMessage() : error.toString());
        });
    }

    /**
     * {@link Span#current()} returns the invalid (no-op) span — never
     * {@code null} — when nothing is current, which is how this silently
     * no-ops instead of needing its own "is a span active" bookkeeping.
     */
    private void withSpan(Consumer<Span> action) {
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            action.accept(span);
        }
    }

    private void end(Consumer<Span> beforeEnd) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            return;
        }
        try {
            beforeEnd.accept(span);
            span.end();
        } finally {
            Deque<Scope> stack = scopeStack.get();
            if (!stack.isEmpty()) {
                stack.pop().close();
            }
        }
    }
}
