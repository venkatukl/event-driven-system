package com.platform.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.error.ErrorCode;
import com.platform.common.error.NonRetryableException;
import com.platform.common.error.RetryableException;
import com.platform.common.factory.ChannelAdapter;
import com.platform.common.factory.ChannelAdapterFactory;
import com.platform.common.kafka.AuditEventPublisher;
import com.platform.common.model.EventEnvelope;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Dispatch service with ASYNC vendor calls + BATCH DB writes.
 *
 * Processing flow per Kafka batch:
 *   1. Receive batch of N records from poll()
 *   2. Submit each record to async thread pool (50 threads)
 *   3. Each thread: rate-limit check → circuit breaker → vendor API call
 *   4. Collect all results into thread-safe queue
 *   5. Single batch INSERT into event_dispatches
 *   6. Single batch UPDATE on events.status
 *   7. Acknowledge Kafka offsets
 *
 * DB round-trips per batch: 2 (not N × 2)
 */
@Service
public class DispatchServiceV2 {

    private static final Logger log = LoggerFactory.getLogger(DispatchServiceV2.class);
    private static final String RETRY_TOPIC = "retry.events";

    private final ChannelAdapterFactory adapterFactory;
    private final CircuitBreakerRegistry cbRegistry;
    private final RateLimiterRegistry rlRegistry;
    private final DispatchBatchRepository batchRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AuditEventPublisher auditPublisher;
    private final ObjectMapper objectMapper;
    private final Executor dispatchExecutor;

    public DispatchServiceV2(ChannelAdapterFactory adapterFactory,
                              CircuitBreakerRegistry cbRegistry,
                              RateLimiterRegistry rlRegistry,
                              DispatchBatchRepository batchRepository,
                              KafkaTemplate<String, String> kafkaTemplate,
                              AuditEventPublisher auditPublisher,
                              ObjectMapper objectMapper,
                              @Qualifier("dispatchExecutor") Executor dispatchExecutor) {
        this.adapterFactory = adapterFactory;
        this.cbRegistry = cbRegistry;
        this.rlRegistry = rlRegistry;
        this.batchRepository = batchRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.auditPublisher = auditPublisher;
        this.objectMapper = objectMapper;
        this.dispatchExecutor = dispatchExecutor;
    }

    @KafkaListener(
            topicPattern = "dispatch\\..*",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        // Thread-safe collection for results from async threads
        ConcurrentLinkedQueue<DispatchOutcome> outcomes = new ConcurrentLinkedQueue<>();
        List<String> retryPayloads = new ArrayList<>();

        // 1. Submit all records to async thread pool
        List<CompletableFuture<Void>> futures = new ArrayList<>(records.size());

        for (ConsumerRecord<String, String> record : records) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

                    // Set MDC for this thread
                    MDC.put("correlationId", envelope.getCorrelationId());
                    MDC.put("eventId", String.valueOf(envelope.getEventId()));

                    DispatchOutcome outcome = dispatchSingle(envelope);
                    outcomes.add(outcome);

                    if (!outcome.success() && outcome.retryable()) {
                        synchronized (retryPayloads) {
                            retryPayloads.add(record.value());
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to process dispatch record", e);
                } finally {
                    MDC.clear();
                }
            }, dispatchExecutor);

            futures.add(future);
        }

        // 2. Wait for ALL async dispatches to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 3. Batch DB writes (single round-trip for all outcomes)
        List<DispatchOutcome> outcomeList = new ArrayList<>(outcomes);
        if (!outcomeList.isEmpty()) {
            batchRepository.batchSaveDispatches(outcomeList);
            log.debug("Batch saved {} dispatch outcomes", outcomeList.size());
        }

        // 4. Batch publish retries to retry.events
        for (String payload : retryPayloads) {
            kafkaTemplate.send(RETRY_TOPIC, payload);
        }

        // 5. Acknowledge entire batch
        ack.acknowledge();

        log.debug("Batch dispatched: total={}, success={}, retry={}, failed={}",
                records.size(),
                outcomeList.stream().filter(DispatchOutcome::success).count(),
                retryPayloads.size(),
                outcomeList.stream().filter(o -> !o.success() && !o.retryable()).count());
    }

    /**
     * Dispatch a single event. Runs on async thread.
     * Returns outcome (never throws — all errors are captured in the result).
     */
    private DispatchOutcome dispatchSingle(EventEnvelope envelope) {
        String channelName = envelope.getChannel().name().toLowerCase();
        long startMs = System.currentTimeMillis();

        try {
            ChannelAdapter adapter = adapterFactory.getAdapter(envelope.getChannel());
            CircuitBreaker cb = cbRegistry.circuitBreaker(channelName);
            RateLimiter rl = rlRegistry.rateLimiter(channelName);

            // Rate limit
            RateLimiter.waitForPermission(rl);

            // Circuit breaker + vendor call
            ChannelAdapter.DispatchResult result = CircuitBreaker.decorateSupplier(
                    cb, () -> adapter.dispatch(envelope)
            ).get();

            long elapsed = System.currentTimeMillis() - startMs;

            return new DispatchOutcome(
                    envelope.getEventId(), envelope.getCorrelationId(),
                    channelName, result.vendorName(), result.vendorMsgId(),
                    result.httpStatus(), elapsed,
                    result.success(), !result.success() && isRetryableStatus(result.httpStatus()),
                    result.errorCode(), result.errorDetail()
            );

        } catch (RequestNotPermitted e) {
            return errorOutcome(envelope, channelName, startMs,
                    ErrorCode.DSP_RATE_001, true);

        } catch (CallNotPermittedException e) {
            return errorOutcome(envelope, channelName, startMs,
                    ErrorCode.DSP_CIRCUIT_001, true);

        } catch (RetryableException e) {
            return errorOutcome(envelope, channelName, startMs,
                    e.getErrorCode(), true);

        } catch (NonRetryableException e) {
            return errorOutcome(envelope, channelName, startMs,
                    e.getErrorCode(), false);

        } catch (Exception e) {
            log.error("Unexpected dispatch error for eventId={}", envelope.getEventId(), e);
            return errorOutcome(envelope, channelName, startMs,
                    ErrorCode.DSP_VENDOR_003, true);
        }
    }

    private DispatchOutcome errorOutcome(EventEnvelope envelope, String channel,
                                          long startMs, ErrorCode errorCode, boolean retryable) {
        return new DispatchOutcome(
                envelope.getEventId(), envelope.getCorrelationId(),
                channel, "UNKNOWN", null,
                0, System.currentTimeMillis() - startMs,
                false, retryable,
                errorCode.getCode(), errorCode.getDescription()
        );
    }

    private boolean isRetryableStatus(int httpStatus) {
        return httpStatus >= 500 || httpStatus == 429 || httpStatus == 408;
    }

    /**
     * Immutable record capturing the outcome of a single dispatch attempt.
     * Collected from async threads, then batch-written to DB.
     */
    public record DispatchOutcome(
            Long eventId,
            String correlationId,
            String channel,
            String vendorName,
            String vendorMsgId,
            int httpStatus,
            long responseTimeMs,
            boolean success,
            boolean retryable,
            String errorCode,
            String errorDetail
    ) {}
}
