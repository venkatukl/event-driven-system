package com.platform.common.config;

import com.fasterxml.jackson.core.JsonParseException;
import com.platform.common.error.NonRetryableException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.common.TopicPartition;

/**
 * Poison pill handler — prevents a single bad message from blocking the consumer.
 *
 * Behaviour:
 *   1. Retryable exceptions: retry 2× with 1s backoff, then send to DLT
 *   2. Non-retryable exceptions: skip retries, send directly to DLT
 *   3. Deserialization errors: send directly to DLT (poison pill)
 *
 * Without this, a malformed message causes infinite reprocessing because
 * the consumer seeks back to the failing offset on every restart.
 */
@Configuration
public class KafkaErrorHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlerConfig.class);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Route failed records to dlt.events topic
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    log.error("Sending to DLT: topic={}, partition={}, offset={}, error={}",
                            record.topic(), record.partition(), record.offset(),
                            ex.getMessage());
                    return new TopicPartition("dlt.events", -1); // -1 = use default partitioner
                }
        );

        // 2 retries, 1 second apart, then DLT
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(1000L, 2)
        );

        // These exceptions skip retries entirely (non-retryable)
        errorHandler.addNotRetryableExceptions(
                NonRetryableException.class,
                JsonParseException.class,
                SerializationException.class,
                IllegalArgumentException.class,
                ClassCastException.class
        );

        return errorHandler;
    }
}
