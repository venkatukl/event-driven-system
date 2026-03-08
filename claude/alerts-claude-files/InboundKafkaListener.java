package com.platform.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.error.ErrorCode;
import com.platform.common.error.NonRetryableException;
import com.platform.common.kafka.AuditEventPublisher;
import com.platform.common.model.EventEnvelope;
import com.platform.common.model.EventStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ingestion service: consumes from all inbound.<upstream> topics,
 * deduplicates, validates schema, enriches, and publishes to processing.events.
 *
 * Uses BATCH listener for throughput. One listener per upstream topic pattern.
 * Static group membership (group.instance.id = HOSTNAME) prevents rebalancing on restart.
 */
@Service
public class InboundKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(InboundKafkaListener.class);
    private static final String PROCESSING_TOPIC = "processing.events";

    private final DeduplicationService deduplicationService;
    private final SchemaValidationService schemaValidationService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AuditEventPublisher auditPublisher;
    private final ObjectMapper objectMapper;

    public InboundKafkaListener(DeduplicationService deduplicationService,
                                 SchemaValidationService schemaValidationService,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 AuditEventPublisher auditPublisher,
                                 ObjectMapper objectMapper) {
        this.deduplicationService = deduplicationService;
        this.schemaValidationService = schemaValidationService;
        this.kafkaTemplate = kafkaTemplate;
        this.auditPublisher = auditPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Batch listener for all inbound topics matching the pattern.
     * Concurrency matches partition count (configured in application.yml).
     */
    @KafkaListener(
            topicPattern = "inbound\\..*",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        for (ConsumerRecord<String, String> record : records) {
            String correlationId = UUID.randomUUID().toString();
            try {
                MDC.put("correlationId", correlationId);
                MDC.put("topic", record.topic());
                MDC.put("partition", String.valueOf(record.partition()));
                MDC.put("offset", String.valueOf(record.offset()));

                processRecord(record, correlationId);

            } catch (NonRetryableException e) {
                log.warn("Non-retryable error processing record: errorCode={}, detail={}",
                        e.getErrorCode().getCode(), e.getMessage());
                auditPublisher.publishFailure(correlationId, null, "ingestion-service",
                        "INGEST", "PROCESS_RECORD", e.getErrorCode(), e.getMessage(), null);
                // Continue with next record — do not break the batch

            } catch (Exception e) {
                log.error("Unexpected error processing record from topic={}, partition={}, offset={}",
                        record.topic(), record.partition(), record.offset(), e);
                auditPublisher.publishFailure(correlationId, null, "ingestion-service",
                        "INGEST", "PROCESS_RECORD", ErrorCode.SYS_KAFKA_001, e.getMessage(), null);

            } finally {
                MDC.clear();
            }
        }
        // Commit offsets after entire batch is processed
        ack.acknowledge();
    }

    private void processRecord(ConsumerRecord<String, String> record, String correlationId) {
        // 1. Parse upstream ID from topic name (e.g., "inbound.banking" → "BANKING")
        String upstreamId = extractUpstreamId(record.topic());

        // 2. Deserialise to EventEnvelope
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        } catch (Exception e) {
            throw new NonRetryableException(ErrorCode.ING_KAFKA_001, correlationId,
                    "Failed to deserialise message: " + e.getMessage());
        }

        envelope.setCorrelationId(correlationId);
        envelope.setUpstreamId(upstreamId);

        // 3. Deduplication check
        String dedupKey = upstreamId + ":" + envelope.getUpstreamMsgId();
        if (deduplicationService.isDuplicate(dedupKey)) {
            log.info("Duplicate detected: upstreamMsgId={}", envelope.getUpstreamMsgId());
            auditPublisher.publishSuccess(correlationId, null, "ingestion-service",
                    "INGEST", "DEDUP_CHECK", Map.of("result", "DUPLICATE", "dedupKey", dedupKey));
            return; // Skip silently
        }

        // 4. Schema validation
        schemaValidationService.validate(upstreamId, envelope);

        // 5. Enrich
        envelope.setStatus(EventStatus.RECEIVED);

        // 6. Publish to processing topic
        String json = serialise(envelope);
        kafkaTemplate.send(PROCESSING_TOPIC, envelope.getCorrelationId(), json);

        // 7. Mark as seen in dedup cache
        deduplicationService.markSeen(dedupKey, null);

        // 8. Audit
        auditPublisher.publishSuccess(correlationId, null, "ingestion-service",
                "INGEST", "RECORD_INGESTED",
                Map.of("upstreamId", upstreamId, "upstreamMsgId", envelope.getUpstreamMsgId()));

        log.debug("Ingested event: upstreamId={}, upstreamMsgId={}", upstreamId, envelope.getUpstreamMsgId());
    }

    private String extractUpstreamId(String topic) {
        // "inbound.banking" → "BANKING"
        int dotIndex = topic.indexOf('.');
        if (dotIndex < 0 || dotIndex == topic.length() - 1) {
            throw new NonRetryableException(ErrorCode.ING_SCHEMA_002, null,
                    "Cannot extract upstream from topic: " + topic);
        }
        return topic.substring(dotIndex + 1).toUpperCase().replace("-", "_");
    }

    private String serialise(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new NonRetryableException(ErrorCode.ING_KAFKA_001, envelope.getCorrelationId(),
                    "Serialisation failed: " + e.getMessage());
        }
    }
}
