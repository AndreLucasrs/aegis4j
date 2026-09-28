package dev.aegis4j.api.usage;

import dev.aegis4j.api.provider.Usage;

/**
 * Opt-in sink for per-call token usage. {@code Aegis4jEngine} calls this once
 * after every successful {@code Provider#complete(...)} when a tracker is
 * configured (see {@code Aegis4jEngine.Builder#usageTracker}); it is never
 * called on its own by anything else, and the engine works exactly as before
 * if no tracker is set. Implementations must be thread-safe, since a single
 * engine instance serves concurrent requests.
 */
public interface UsageTracker {

    void record(String providerId, String model, Usage usage);
}
