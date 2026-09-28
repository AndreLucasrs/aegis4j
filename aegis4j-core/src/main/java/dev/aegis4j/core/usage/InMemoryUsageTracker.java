package dev.aegis4j.core.usage;

import dev.aegis4j.api.provider.Usage;
import dev.aegis4j.api.usage.UsageTracker;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-local {@link UsageTracker} that aggregates token counts per
 * provider+model pair. Resets on JVM restart — a consumer that needs
 * persistence or cross-instance aggregation should wrap or replace this with
 * one backed by a database or metrics system; this is meant to cover the
 * common "how many tokens has this app burned today" case with zero setup.
 *
 * <p><b>{@code model} key:</b> {@code Aegis4jEngine} calls {@link #record}
 * with the model that was actually requested (i.e. {@code ChatRequest}'s
 * explicit model, or whatever a {@code ModelRouter} resolved it to) — not
 * necessarily the exact string a provider echoes back on
 * {@code CompletionResponse#model()}, which for some providers is a more
 * specific dated snapshot (e.g. requesting {@code "gpt-4o"} can come back as
 * {@code "gpt-4o-2024-08-06"}). This matters for {@link #estimatedCost}: key
 * {@link PricingRate} entries by the model name your {@code ChatRequest}s
 * actually use, not by a provider-reported alias, or the lookup will miss and
 * {@link #estimatedCost} will silently return {@code 0}.
 *
 * <p>Optionally priced via a {@link PricingRate} per model, so
 * {@link #estimatedCost(String, String)} can turn aggregated usage into an
 * approximate cost without a separate billing integration.
 */
public final class InMemoryUsageTracker implements UsageTracker {

    private final ConcurrentMap<UsageKey, Totals> totalsByKey = new ConcurrentHashMap<>();
    private final Map<String, PricingRate> pricingByModel;

    public InMemoryUsageTracker() {
        this(Map.of());
    }

    public InMemoryUsageTracker(Map<String, PricingRate> pricingByModel) {
        this.pricingByModel = Map.copyOf(pricingByModel);
    }

    @Override
    public void record(String providerId, String model, Usage usage) {
        Totals totals = totalsByKey.computeIfAbsent(new UsageKey(providerId, model), key -> new Totals());
        totals.promptTokens.add(usage.promptTokens());
        totals.completionTokens.add(usage.completionTokens());
        totals.totalTokens.add(usage.totalTokens());
        totals.calls.increment();
    }

    public long promptTokens(String providerId, String model) {
        Totals totals = totalsByKey.get(new UsageKey(providerId, model));
        return totals == null ? 0L : totals.promptTokens.sum();
    }

    public long completionTokens(String providerId, String model) {
        Totals totals = totalsByKey.get(new UsageKey(providerId, model));
        return totals == null ? 0L : totals.completionTokens.sum();
    }

    public long totalTokens(String providerId, String model) {
        Totals totals = totalsByKey.get(new UsageKey(providerId, model));
        return totals == null ? 0L : totals.totalTokens.sum();
    }

    public long callCount(String providerId, String model) {
        Totals totals = totalsByKey.get(new UsageKey(providerId, model));
        return totals == null ? 0L : totals.calls.sum();
    }

    /**
     * {@code promptCostPer1kTokens * promptTokens / 1000 + completionCostPer1kTokens
     * * completionTokens / 1000}, or {@code 0} when no {@link PricingRate} was
     * configured for this model (see the class Javadoc for which string
     * {@code model} needs to be — the requested model, not necessarily what
     * a provider echoes back).
     *
     * <p>Reads {@code promptTokens}/{@code completionTokens} from two
     * separate {@link LongAdder}s without a shared lock, same caveat as
     * {@link #snapshot()}: under concurrent {@link #record} calls this can
     * mix pre- and post-update values across the two counters. Acceptable
     * for an approximate running cost; not a linearizable read.
     */
    public double estimatedCost(String providerId, String model) {
        PricingRate rate = pricingByModel.get(model);
        Totals totals = totalsByKey.get(new UsageKey(providerId, model));
        if (rate == null || totals == null) {
            return 0.0;
        }
        return (totals.promptTokens.sum() / 1000.0) * rate.promptCostPer1kTokens()
                + (totals.completionTokens.sum() / 1000.0) * rate.completionCostPer1kTokens();
    }

    /**
     * A point-in-time copy of every provider+model pair seen so far.
     *
     * <p>Two caveats inherited from using independent {@link LongAdder}s per
     * counter for lock-free writes:
     * <ul>
     *   <li>Not linearizable across counters — a concurrent {@link #record}
     *   can be observed as having updated {@code promptTokens} but not yet
     *   {@code completionTokens}/{@code totalTokens} (or vice versa), so a
     *   snapshot row is not guaranteed to satisfy
     *   {@code totalTokens == promptTokens + completionTokens} under
     *   concurrent writers. Each individual counter is still exact once
     *   writers quiesce; this is a consistency caveat for concurrent reads,
     *   not a correctness bug in the totals themselves.
     *   <li>{@link Usage}'s fields are {@code int}, but the underlying
     *   counters are {@code long}. A sum that exceeds
     *   {@link Integer#MAX_VALUE} (over ~2.1 billion tokens for one
     *   provider+model pair) saturates at {@link Integer#MAX_VALUE} here
     *   instead of silently wrapping into a negative number. Use
     *   {@link #promptTokens}, {@link #completionTokens}, {@link #totalTokens}
     *   (all {@code long}) directly for exact figures at that scale.
     * </ul>
     */
    public Map<UsageKey, Usage> snapshot() {
        Map<UsageKey, Usage> snapshot = new HashMap<>();
        totalsByKey.forEach((key, totals) -> snapshot.put(key, new Usage(
                saturateToInt(totals.promptTokens.sum()),
                saturateToInt(totals.completionTokens.sum()),
                saturateToInt(totals.totalTokens.sum())
        )));
        return Map.copyOf(snapshot);
    }

    private static int saturateToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    public record UsageKey(String providerId, String model) {
    }

    /** Cost per 1,000 tokens, split prompt vs. completion since providers commonly price them differently. */
    public record PricingRate(double promptCostPer1kTokens, double completionCostPer1kTokens) {
    }

    private static final class Totals {
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();
        private final LongAdder totalTokens = new LongAdder();
        private final LongAdder calls = new LongAdder();
    }
}
