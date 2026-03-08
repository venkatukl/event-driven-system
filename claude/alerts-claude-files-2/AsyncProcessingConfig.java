package com.platform.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async thread pool for processing records within a Kafka batch.
 *
 * MEMORY BUDGET (8 cores, 3GB per pod):
 *   - 50 threads × 512KB stack = 25MB
 *   - Threads are I/O-bound (vendor HTTP calls), so 50 threads on 8 cores is optimal
 *   - Queue capacity = 0 (caller-runs policy provides natural backpressure)
 *
 * WHY NOT 200 THREADS?
 *   - 200 × 1MB default stack = 200MB (wasteful)
 *   - With 8 cores, anything above ~80 threads gives zero additional parallelism
 *   - Risk of OOM under burst load
 *
 * JDK 21+ ALTERNATIVE:
 *   Set spring.threads.virtual.enabled=true and use virtual threads instead.
 *   Virtual threads have ~1KB stack, allowing 1000+ concurrent without memory risk.
 */
@Configuration
public class AsyncProcessingConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncProcessingConfig.class);

    @Value("${platform.async.core-pool-size:20}")
    private int corePoolSize;

    @Value("${platform.async.max-pool-size:50}")
    private int maxPoolSize;

    @Value("${platform.async.queue-capacity:100}")
    private int queueCapacity;

    @Value("${platform.async.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    /**
     * Thread pool for async dispatch within a Kafka batch.
     *
     * Usage pattern:
     *   1. Consumer thread polls 500 records
     *   2. Submit each record to this pool as CompletableFuture
     *   3. CompletableFuture.allOf(...).join() — wait for all
     *   4. Batch-write results to DB
     *   5. Acknowledge Kafka offset
     *
     * CallerRunsPolicy: If pool is saturated, the consumer thread
     * processes the record itself — natural backpressure that slows
     * Kafka polling without losing messages.
     */
    @Bean("dispatchExecutor")
    public Executor dispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("dispatch-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(25); // align with Kafka shutdown timeout
        executor.initialize();

        log.info("Dispatch async pool: core={}, max={}, queue={}", corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }

    /**
     * Separate pool for non-critical async work (audit publishing, metrics).
     * Smaller pool since audit failures must never block the main path.
     */
    @Bean("auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);     // larger queue — audit can tolerate delay
        executor.setThreadNamePrefix("audit-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy()); // drop oldest audit if saturated
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
