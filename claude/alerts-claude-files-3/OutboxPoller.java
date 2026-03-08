package com.platform.processing.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Outbox Poller — ensures DB-to-Kafka consistency.
 *
 * PROBLEM:
 *   INSERT events → COMMIT → kafkaTemplate.send() → (Kafka fails) → message lost
 *
 * SOLUTION:
 *   INSERT events + INSERT outbox → COMMIT (atomic)
 *   This poller reads PENDING outbox rows → sends to Kafka → marks SENT
 *
 * The outbox guarantees: if DB committed, the message WILL reach Kafka.
 * At-least-once delivery (idempotency on consumer side handles duplicates).
 *
 * Polling interval: 100ms for near-real-time. Under load, each poll
 * processes up to 500 rows (aligned with Kafka batch size).
 *
 * ALTERNATIVE: Use Debezium CDC connector to stream outbox table changes
 * to Kafka with zero polling. Better for very high throughput.
 */
@Service
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPoller(JdbcTemplate jdbcTemplate,
                         KafkaTemplate<String, String> kafkaTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Poll outbox every 100ms. Uses SELECT FOR UPDATE SKIP LOCKED
     * so multiple pods can run this safely without processing the same row.
     */
    @Scheduled(fixedDelay = 100)
    @Transactional
    public void pollAndPublish() {
        // SELECT FOR UPDATE SKIP LOCKED — safe for concurrent pods
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT outbox_id, aggregate_id, topic, partition_key, payload
                FROM event_outbox
                WHERE status = 'PENDING'
                ORDER BY created_at
                FETCH FIRST ? ROWS ONLY
                FOR UPDATE SKIP LOCKED
                """, BATCH_SIZE);

        if (rows.isEmpty()) return;

        int sent = 0;
        for (Map<String, Object> row : rows) {
            Long outboxId = ((Number) row.get("OUTBOX_ID")).longValue();
            String topic = (String) row.get("TOPIC");
            String key = (String) row.get("PARTITION_KEY");
            String payload = (String) row.get("PAYLOAD");

            try {
                kafkaTemplate.send(topic, key, payload).get();  // synchronous send for guaranteed delivery

                jdbcTemplate.update(
                        "UPDATE event_outbox SET status = 'SENT', sent_at = SYSTIMESTAMP WHERE outbox_id = ?",
                        outboxId);
                sent++;
            } catch (Exception e) {
                log.error("Outbox send failed: outboxId={}, topic={}", outboxId, topic, e);
                jdbcTemplate.update(
                        "UPDATE event_outbox SET status = 'FAILED' WHERE outbox_id = ?",
                        outboxId);
                // FAILED rows will be retried by a separate recovery job
            }
        }

        if (sent > 0) {
            log.debug("Outbox published {} messages", sent);
        }
    }
}
