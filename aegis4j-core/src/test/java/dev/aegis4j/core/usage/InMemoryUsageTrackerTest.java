package dev.aegis4j.core.usage;

import dev.aegis4j.api.provider.Usage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryUsageTrackerTest {

    @Test
    void aggregatesUsageByProviderAndModel() {
        InMemoryUsageTracker tracker = new InMemoryUsageTracker();

        tracker.record("openai", "gpt-4o", new Usage(10, 20, 30));
        tracker.record("openai", "gpt-4o", new Usage(5, 15, 20));

        assertThat(tracker.promptTokens("openai", "gpt-4o")).isEqualTo(15);
        assertThat(tracker.completionTokens("openai", "gpt-4o")).isEqualTo(35);
        assertThat(tracker.totalTokens("openai", "gpt-4o")).isEqualTo(50);
        assertThat(tracker.callCount("openai", "gpt-4o")).isEqualTo(2);
    }

    @Test
    void keepsProviderAndModelPairsSeparate() {
        InMemoryUsageTracker tracker = new InMemoryUsageTracker();

        tracker.record("openai", "gpt-4o", new Usage(10, 10, 20));
        tracker.record("ollama", "llama3", new Usage(1, 1, 2));
        tracker.record("openai", "gpt-4o-mini", new Usage(3, 3, 6));

        assertThat(tracker.totalTokens("openai", "gpt-4o")).isEqualTo(20);
        assertThat(tracker.totalTokens("ollama", "llama3")).isEqualTo(2);
        assertThat(tracker.totalTokens("openai", "gpt-4o-mini")).isEqualTo(6);
    }

    @Test
    void returnsZeroForUnseenProviderAndModel() {
        InMemoryUsageTracker tracker = new InMemoryUsageTracker();

        assertThat(tracker.totalTokens("unknown", "unknown")).isZero();
        assertThat(tracker.callCount("unknown", "unknown")).isZero();
    }

    @Test
    void snapshotReflectsAllRecordedPairs() {
        InMemoryUsageTracker tracker = new InMemoryUsageTracker();

        tracker.record("openai", "gpt-4o", new Usage(10, 10, 20));
        tracker.record("ollama", "llama3", new Usage(1, 1, 2));

        Map<InMemoryUsageTracker.UsageKey, Usage> snapshot = tracker.snapshot();

        assertThat(snapshot).containsEntry(new InMemoryUsageTracker.UsageKey("openai", "gpt-4o"), new Usage(10, 10, 20));
        assertThat(snapshot).containsEntry(new InMemoryUsageTracker.UsageKey("ollama", "llama3"), new Usage(1, 1, 2));
    }

    @Test
    void estimatesCostFromConfiguredPricing() {
        InMemoryUsageTracker tracker = new InMemoryUsageTracker(
                Map.of("gpt-4o", new InMemoryUsageTracker.PricingRate(0.005, 0.015))
        );

        tracker.record("openai", "gpt-4o", new Usage(1000, 1000, 2000));

        assertThat(tracker.estimatedCost("openai", "gpt-4o")).isEqualTo(0.02);
    }

    @Test
    void estimatedCostIsZeroWithoutConfiguredPricing() {
        InMemoryUsageTracker tracker = new InMemoryUsageTracker();

        tracker.record("openai", "gpt-4o", new Usage(1000, 1000, 2000));

        assertThat(tracker.estimatedCost("openai", "gpt-4o")).isZero();
    }

    @Test
    void recordIsThreadSafeUnderConcurrentWrites() throws InterruptedException {
        InMemoryUsageTracker tracker = new InMemoryUsageTracker();
        int threadCount = 16;
        int recordsPerThread = 1_000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < recordsPerThread; j++) {
                        tracker.record("openai", "gpt-4o", new Usage(1, 2, 3));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        int expectedCalls = threadCount * recordsPerThread;
        assertThat(tracker.callCount("openai", "gpt-4o")).isEqualTo(expectedCalls);
        assertThat(tracker.promptTokens("openai", "gpt-4o")).isEqualTo(expectedCalls);
        assertThat(tracker.completionTokens("openai", "gpt-4o")).isEqualTo(expectedCalls * 2L);
        assertThat(tracker.totalTokens("openai", "gpt-4o")).isEqualTo(expectedCalls * 3L);
    }
}
