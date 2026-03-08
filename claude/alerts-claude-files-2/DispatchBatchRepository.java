package com.platform.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * Batch-optimised repository for EVENT_DISPATCHES table.
 *
 * Instead of N individual INSERTs per Kafka batch, executes a single
 * JDBC batch INSERT for all dispatch outcomes.
 *
 * At 500 records/batch and 60 batches/second:
 *   - Individual: 30,000 INSERT round-trips/second
 *   - Batched:    60 INSERT round-trips/second (500× reduction)
 */
@Repository
public class DispatchBatchRepository {

    private static final Logger log = LoggerFactory.getLogger(DispatchBatchRepository.class);

    private static final String INSERT_DISPATCH_SQL = """
            INSERT INTO event_dispatches (
                dispatch_id, event_id, channel, vendor_name, vendor_msg_id,
                status, error_code, error_detail, http_status, response_time_ms,
                attempted_at, created_at
            ) VALUES (
                seq_dispatch_id.NEXTVAL, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                SYSTIMESTAMP, SYSTIMESTAMP
            )
            """;

    private static final String BATCH_UPDATE_EVENT_STATUS = """
            UPDATE events SET status = ?, error_code = ?,
                updated_at = SYSTIMESTAMP, version = version + 1
            WHERE event_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public DispatchBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Batch-insert all dispatch outcomes from a single Kafka poll batch.
     * Also batch-updates the parent events table status.
     */
    public void batchSaveDispatches(List<DispatchServiceV2.DispatchOutcome> outcomes) {
        if (outcomes.isEmpty()) return;

        // 1. Batch INSERT into event_dispatches
        jdbcTemplate.batchUpdate(INSERT_DISPATCH_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                DispatchServiceV2.DispatchOutcome o = outcomes.get(i);
                ps.setLong(1, o.eventId());
                ps.setString(2, o.channel());
                ps.setString(3, o.vendorName());
                if (o.vendorMsgId() != null) {
                    ps.setString(4, o.vendorMsgId());
                } else {
                    ps.setNull(4, Types.VARCHAR);
                }
                ps.setString(5, o.success() ? "SENT" : "FAILED");
                ps.setString(6, o.errorCode());
                ps.setString(7, o.errorDetail());
                ps.setInt(8, o.httpStatus());
                ps.setLong(9, o.responseTimeMs());
            }

            @Override
            public int getBatchSize() {
                return outcomes.size();
            }
        });

        // 2. Batch UPDATE events.status
        jdbcTemplate.batchUpdate(BATCH_UPDATE_EVENT_STATUS, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                DispatchServiceV2.DispatchOutcome o = outcomes.get(i);
                ps.setString(1, o.success() ? "DISPATCHED" : (o.retryable() ? "RETRYING" : "FAILED"));
                ps.setString(2, o.errorCode());
                ps.setLong(3, o.eventId());
            }

            @Override
            public int getBatchSize() {
                return outcomes.size();
            }
        });

        log.debug("Batch persisted {} dispatch outcomes (2 DB round-trips)", outcomes.size());
    }
}
