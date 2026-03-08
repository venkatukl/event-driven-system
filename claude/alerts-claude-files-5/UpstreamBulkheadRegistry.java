package com.platform.common.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-upstream bulkhead registry — prevents one upstream from starving others.
 *
 * PROBLEM (at 15 upstreams):
 *   If upstream #12 sends payloads that cause slow regex validation (3 seconds each),
 *   it consumes all 50 async threads. Upstreams #1–11 and #13–15 can't process.
 *   OTP messages from Banking are delayed because Marketing's processor is slow.
 *
 * SOLUTION:
 *   Each upstream gets a bulkhead limiting its max concurrent processing threads.
 *   Default = maxConcurrentCalls / number of upstreams (fair share).
 *   High-priority upstreams (OTP, Banking) can be configured with larger bulkheads.
 *
 * USAGE:
 *   Bulkhead bulkhead = bulkheadRegistry.getBulkhead("BANKING");
 *   Bulkhead.decorateRunnable(bulkhead, () -> processEvent(envelope)).run();
 *
 * If the bulkhead is full, the CallerRunsPolicy on the thread pool ensures
 * the consumer thread processes it directly — natural backpressure that slows
 * Kafka polling for that upstream without affecting others.
 */
@Component
public class UpstreamBulkheadRegistry {

    private static final Logger log = LoggerFactory.getLogger(UpstreamBulkheadRegistry.class);

    // Default: 10 concurrent calls per upstream
    // With 50 thread pool and 15 upstreams, this gives fair share of ~3.3 threads each
    // High-priority upstreams can be configured higher
    private static final int DEFAULT_MAX_CONCURRENT = 10;
    private static final Duration DEFAULT_MAX_WAIT = Duration.ofMillis(500);

    private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    private final Map<String, Integer> customLimits = new ConcurrentHashMap<>();

    /**
     * Get or create a bulkhead for the given upstream.
     * Thread-safe via ConcurrentHashMap.computeIfAbsent.
     */
    public Bulkhead getBulkhead(String upstreamId) {
        return bulkheads.computeIfAbsent(upstreamId, id -> {
            int maxConcurrent = customLimits.getOrDefault(id, DEFAULT_MAX_CONCURRENT);

            BulkheadConfig config = BulkheadConfig.custom()
                    .maxConcurrentCalls(maxConcurrent)
                    .maxWaitDuration(DEFAULT_MAX_WAIT)
                    .writableStackTraceEnabled(false) // reduce overhead in hot path
                    .build();

            Bulkhead bh = Bulkhead.of("upstream-" + id, config);

            log.info("Created bulkhead for upstream={}, maxConcurrent={}", id, maxConcurrent);
            return bh;
        });
    }

    /**
     * Configure a custom concurrency limit for a specific upstream.
     * Call this during startup from upstream_config.rate_limit_per_sec
     * or from a config refresh.
     */
    public void configureLimit(String upstreamId, int maxConcurrent) {
        customLimits.put(upstreamId, maxConcurrent);
        // If bulkhead already exists, recreate it with new limit
        bulkheads.remove(upstreamId);
        log.info("Configured bulkhead limit for upstream={}: maxConcurrent={}", upstreamId, maxConcurrent);
    }

    /**
     * Get metrics for monitoring.
     */
    public Map<String, BulkheadMetrics> getMetrics() {
        Map<String, BulkheadMetrics> metrics = new ConcurrentHashMap<>();
        bulkheads.forEach((id, bh) -> {
            Bulkhead.Metrics m = bh.getMetrics();
            metrics.put(id, new BulkheadMetrics(
                    m.getAvailableConcurrentCalls(),
                    m.getMaxAllowedConcurrentCalls()
            ));
        });
        return metrics;
    }

    public record BulkheadMetrics(int available, int maxAllowed) {}
}
