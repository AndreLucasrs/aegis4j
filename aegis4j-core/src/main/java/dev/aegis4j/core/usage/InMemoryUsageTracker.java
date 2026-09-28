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
     * configured for this model.
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

    /** A point-in-time copy of every provider+model pair seen so far. */
    public Map<UsageKey, Usage> snapshot() {
        Map<UsageKey, Usage> snapshot = new HashMap<>();
        totalsByKey.forEach((key, totals) -> snapshot.put(key, new Usage(
                (int) totals.promptTokens.sum(),
                (int) totals.completionTokens.sum(),
                (int) totals.totalTokens.sum()
        )));
        return Map.copyOf(snapshot);
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
