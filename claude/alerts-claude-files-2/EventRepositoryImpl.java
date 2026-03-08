package com.platform.processing;

import com.platform.common.model.EventEnvelope;
import com.platform.common.model.EventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch-optimised repository for EVENTS table.
 *
 * KEY DESIGN DECISION: At 30K msg/s, individual INSERTs would create 30K DB
 * round-trips per second. Instead, we batch-insert aligned with Kafka poll
 * batch size (500 records). This reduces DB round-trips from 30,000/s to 60/s.
 *
 * Uses JDBC batch (not JPA) for maximum throughput and predictable memory usage.
 */
@Repository
public class EventRepositoryImpl implements EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventRepositoryImpl.class);

    private static final String INSERT_SQL = """
            INSERT INTO events (
                event_id, correlation_id, upstream_id, upstream_msg_id,
                channel, priority, recipient, subject, payload, template_id,
                status, retry_count, max_retries, scheduled_at, expires_at,
                created_at, updated_at, created_by, version
            ) VALUES (
                seq_event_id.NEXTVAL, ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                SYSTIMESTAMP, SYSTIMESTAMP, 'SYSTEM', 0
            )
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE events
            SET status = ?, error_code = ?, error_detail = ?,
                updated_at = SYSTIMESTAMP, version = version + 1
            WHERE event_id = ? AND version = ?
            """;

    private static final String BATCH_UPDATE_STATUS_SQL = """
            UPDATE events
            SET status = ?, updated_at = SYSTIMESTAMP, version = version + 1
            WHERE event_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public EventRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Single Insert (fallback, avoid in hot path) ──

    @Override
    public Long save(EventEnvelope envelope) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_SQL, new String[]{"event_id"});
            setInsertParams(ps, envelope);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    // ── Batch Insert (primary path — called per Kafka batch) ──

    /**
     * Batch-insert a list of events in a single DB round-trip.
     * Called once per Kafka poll batch (typically 500 records).
     *
     * Returns generated event IDs. Uses Oracle's RETURNING INTO
     * via a callback pattern since JDBC batch + generated keys is tricky.
     *
     * Practical approach: pre-fetch sequence values, then batch insert.
     */
    public List<Long> batchSave(List<EventEnvelope> envelopes) {
        if (envelopes.isEmpty()) return List.of();

        int batchSize = envelopes.size();

        // 1. Pre-fetch sequence values in one call (Oracle-specific optimisation)
        List<Long> ids = preFetchSequenceValues(batchSize);

        // 2. Assign IDs to envelopes
        for (int i = 0; i < batchSize; i++) {
            envelopes.get(i).setEventId(ids.get(i));
        }

        // 3. Batch insert with pre-assigned IDs
        String insertWithId = """
                INSERT INTO events (
                    event_id, correlation_id, upstream_id, upstream_msg_id,
                    channel, priority, recipient, subject, payload, template_id,
                    status, retry_count, max_retries, scheduled_at, expires_at,
                    created_at, updated_at, created_by, version
                ) VALUES (
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    SYSTIMESTAMP, SYSTIMESTAMP, 'SYSTEM', 0
                )
                """;

        jdbcTemplate.batchUpdate(insertWithId, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                EventEnvelope e = envelopes.get(i);
                ps.setLong(1, e.getEventId());
                ps.setString(2, e.getCorrelationId());
                ps.setString(3, e.getUpstreamId());
                ps.setString(4, e.getUpstreamMsgId());
                ps.setString(5, e.getChannel() != null ? e.getChannel().name() : null);
                ps.setInt(6, e.getPriority());
                ps.setString(7, e.getRecipient());
                ps.setString(8, e.getSubject());
                ps.setString(9, e.getPayload());
                ps.setString(10, e.getTemplateId());
                ps.setString(11, EventStatus.PROCESSING.name());
                ps.setInt(12, e.getRetryCount());
                ps.setInt(13, e.getMaxRetries());
                if (e.getScheduledAt() != null) {
                    ps.setTimestamp(14, Timestamp.from(e.getScheduledAt()));
                } else {
                    ps.setNull(14, Types.TIMESTAMP);
                }
                if (e.getExpiresAt() != null) {
                    ps.setTimestamp(15, Timestamp.from(e.getExpiresAt()));
                } else {
                    ps.setNull(15, Types.TIMESTAMP);
                }
            }

            @Override
            public int getBatchSize() {
                return envelopes.size();
            }
        });

        log.debug("Batch inserted {} events", batchSize);
        return ids;
    }

    // ── Batch Status Update ──

    /**
     * Batch-update status for multiple events in a single round-trip.
     * Used after async dispatch completes for the entire batch.
     */
    public int[] batchUpdateStatus(List<Long> eventIds, EventStatus status) {
        return jdbcTemplate.batchUpdate(BATCH_UPDATE_STATUS_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setString(1, status.name());
                ps.setLong(2, eventIds.get(i));
            }

            @Override
            public int getBatchSize() {
                return eventIds.size();
            }
        });
    }

    // ── Single Status Update (with optimistic locking) ──

    @Override
    public void updateStatus(Long eventId, EventStatus status, String errorCode,
                              String errorDetail, int expectedVersion) {
        int updated = jdbcTemplate.update(UPDATE_STATUS_SQL,
                status.name(), errorCode, errorDetail, eventId, expectedVersion);
        if (updated == 0) {
            log.warn("Optimistic lock conflict: eventId={}, expectedVersion={}", eventId, expectedVersion);
        }
    }

    @Override
    public void incrementRetryCount(Long eventId) {
        jdbcTemplate.update(
                "UPDATE events SET retry_count = retry_count + 1, updated_at = SYSTIMESTAMP WHERE event_id = ?",
                eventId);
    }

    // ── Oracle Sequence Pre-Fetch ──

    /**
     * Pre-fetch N sequence values in a single DB call.
     * Uses Oracle's CONNECT BY trick to generate multiple values.
     *
     * This avoids N individual NEXTVAL calls during batch insert.
     * At batch=500, this reduces sequence calls from 500 to 1.
     */
    private List<Long> preFetchSequenceValues(int count) {
        String sql = """
                SELECT seq_event_id.NEXTVAL FROM DUAL
                CONNECT BY LEVEL <= ?
                """;
        return jdbcTemplate.queryForList(sql, Long.class, count);
    }

    // ── Helper ──

    private void setInsertParams(PreparedStatement ps, EventEnvelope e) throws SQLException {
        ps.setString(1, e.getCorrelationId());
        ps.setString(2, e.getUpstreamId());
        ps.setString(3, e.getUpstreamMsgId());
        ps.setString(4, e.getChannel() != null ? e.getChannel().name() : null);
        ps.setInt(5, e.getPriority());
        ps.setString(6, e.getRecipient());
        ps.setString(7, e.getSubject());
        ps.setString(8, e.getPayload());
        ps.setString(9, e.getTemplateId());
        ps.setString(10, EventStatus.PROCESSING.name());
        ps.setInt(11, e.getRetryCount());
        ps.setInt(12, e.getMaxRetries());
        if (e.getScheduledAt() != null) {
            ps.setTimestamp(13, Timestamp.from(e.getScheduledAt()));
        } else {
            ps.setNull(13, Types.TIMESTAMP);
        }
        if (e.getExpiresAt() != null) {
            ps.setTimestamp(14, Timestamp.from(e.getExpiresAt()));
        } else {
            ps.setNull(14, Types.TIMESTAMP);
        }
    }
}
