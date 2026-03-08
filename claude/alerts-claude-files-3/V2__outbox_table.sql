-- ═══════════════════════════════════════════════════════════
-- OUTBOX PATTERN — Transactional Outbox Table
-- Ensures DB commit + Kafka publish are atomic.
-- ═══════════════════════════════════════════════════════════

CREATE SEQUENCE seq_outbox_id START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;

CREATE TABLE event_outbox (
    outbox_id       NUMBER(19)    DEFAULT seq_outbox_id.NEXTVAL PRIMARY KEY,
    aggregate_id    NUMBER(19)    NOT NULL,
    topic           VARCHAR2(128) NOT NULL,
    partition_key   VARCHAR2(128),
    payload         CLOB          NOT NULL,
    status          VARCHAR2(10)  DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    created_at      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
    sent_at         TIMESTAMP
);

-- Partial index for efficient polling (only PENDING rows)
CREATE INDEX idx_outbox_pending ON event_outbox (status, created_at);

-- Cleanup job: delete SENT rows older than 1 hour
CREATE OR REPLACE PROCEDURE purge_sent_outbox AS
BEGIN
    DELETE FROM event_outbox
    WHERE status = 'SENT' AND sent_at < SYSTIMESTAMP - INTERVAL '1' HOUR;
    COMMIT;
END;
/

BEGIN
    DBMS_SCHEDULER.CREATE_JOB (
        job_name        => 'JOB_PURGE_OUTBOX',
        job_type        => 'STORED_PROCEDURE',
        job_action      => 'purge_sent_outbox',
        start_date      => SYSTIMESTAMP,
        repeat_interval => 'FREQ=MINUTELY;INTERVAL=10',
        enabled         => TRUE,
        comments        => 'Purge sent outbox entries every 10 minutes'
    );
END;
/
