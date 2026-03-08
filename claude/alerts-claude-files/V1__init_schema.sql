-- ═══════════════════════════════════════════════════════════════════════
-- Event Processing Platform — Oracle DDL
-- Version: 1.0.0
-- ═══════════════════════════════════════════════════════════════════════

-- ───────────────────────────────────────────────────────────
-- SEQUENCES (CACHE 1000 for high-throughput, NOORDER for performance)
-- NUMBER(19) → Java long, lasts ~9,700 years at 30K/s
-- ───────────────────────────────────────────────────────────
CREATE SEQUENCE seq_event_id      START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;
CREATE SEQUENCE seq_dispatch_id   START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;
CREATE SEQUENCE seq_retry_id      START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;
CREATE SEQUENCE seq_audit_id      START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;
CREATE SEQUENCE seq_callback_id   START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;

-- ───────────────────────────────────────────────────────────
-- UPSTREAM_CONFIG
-- ───────────────────────────────────────────────────────────
CREATE TABLE upstream_config (
    upstream_id         VARCHAR2(32)    PRIMARY KEY,
    display_name        VARCHAR2(128)   NOT NULL,
    kafka_topic         VARCHAR2(128)   NOT NULL,
    processor_bean      VARCHAR2(128)   NOT NULL,
    default_channel     VARCHAR2(20),
    max_retries         NUMBER(3)       DEFAULT 3,
    priority            NUMBER(2)       DEFAULT 5,
    rate_limit_per_sec  NUMBER(10)      DEFAULT 1000,
    is_active           NUMBER(1)       DEFAULT 1 CHECK (is_active IN (0, 1)),
    config_json         CLOB,
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL
);

COMMENT ON TABLE upstream_config IS 'Registry of upstream input channels. Adding a new upstream requires only a row here + a Spring bean.';

-- ───────────────────────────────────────────────────────────
-- CHANNEL_VENDOR_CONFIG
-- ───────────────────────────────────────────────────────────
CREATE TABLE channel_vendor_config (
    channel             VARCHAR2(20)    NOT NULL,
    vendor_name         VARCHAR2(64)    NOT NULL,
    is_primary          NUMBER(1)       DEFAULT 1 CHECK (is_primary IN (0, 1)),
    priority            NUMBER(2)       DEFAULT 1,
    rate_limit_per_sec  NUMBER(10)      DEFAULT 500,
    circuit_breaker_cfg VARCHAR2(512),
    endpoint_url        VARCHAR2(512)   NOT NULL,
    auth_type           VARCHAR2(20)    DEFAULT 'API_KEY',
    timeout_ms          NUMBER(10)      DEFAULT 5000,
    is_active           NUMBER(1)       DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_channel_vendor PRIMARY KEY (channel, vendor_name)
);

COMMENT ON TABLE channel_vendor_config IS 'Vendor routing config per dispatch channel. Supports primary/fallback vendor failover.';

-- ───────────────────────────────────────────────────────────
-- EVENTS (partitioned by month on created_at)
-- ───────────────────────────────────────────────────────────
CREATE TABLE events (
    event_id            NUMBER(19)      DEFAULT seq_event_id.NEXTVAL PRIMARY KEY,
    correlation_id      VARCHAR2(64)    NOT NULL,
    upstream_id         VARCHAR2(32)    NOT NULL,
    upstream_msg_id     VARCHAR2(128)   NOT NULL,
    channel             VARCHAR2(20)    NOT NULL,
    priority            NUMBER(2)       DEFAULT 5,
    recipient           VARCHAR2(256)   NOT NULL,
    subject             VARCHAR2(512),
    payload             CLOB            NOT NULL,
    template_id         VARCHAR2(64),
    status              VARCHAR2(20)    DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED','VALIDATED','PROCESSING','DISPATCHED',
                          'DELIVERED','FAILED','RETRYING','DLT','EXPIRED')),
    error_code          VARCHAR2(20),
    error_detail        VARCHAR2(2000),
    retry_count         NUMBER(3)       DEFAULT 0,
    max_retries         NUMBER(3)       DEFAULT 3,
    scheduled_at        TIMESTAMP,
    expires_at          TIMESTAMP,
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    created_by          VARCHAR2(64)    DEFAULT 'SYSTEM',
    version             NUMBER(10)      DEFAULT 0
)
PARTITION BY RANGE (created_at)
INTERVAL (NUMTOYMINTERVAL(1, 'MONTH'))
(
    PARTITION p_initial VALUES LESS THAN (TIMESTAMP '2025-07-01 00:00:00')
);

CREATE UNIQUE INDEX idx_events_dedup    ON events (upstream_id, upstream_msg_id) LOCAL;
CREATE INDEX idx_events_status          ON events (status, created_at) LOCAL;
CREATE INDEX idx_events_corr            ON events (correlation_id) LOCAL;
CREATE INDEX idx_events_channel         ON events (channel, status) LOCAL;
CREATE INDEX idx_events_recipient       ON events (recipient, created_at) LOCAL;

COMMENT ON TABLE events IS 'Master event record. Partitioned monthly for archival.';

-- ───────────────────────────────────────────────────────────
-- EVENT_DISPATCHES
-- ───────────────────────────────────────────────────────────
CREATE TABLE event_dispatches (
    dispatch_id         NUMBER(19)      DEFAULT seq_dispatch_id.NEXTVAL PRIMARY KEY,
    event_id            NUMBER(19)      NOT NULL REFERENCES events(event_id),
    channel             VARCHAR2(20)    NOT NULL,
    vendor_name         VARCHAR2(64)    NOT NULL,
    vendor_msg_id       VARCHAR2(256),
    status              VARCHAR2(20)    DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','SENT','DELIVERED','FAILED','EXPIRED','REJECTED')),
    error_code          VARCHAR2(20),
    error_detail        VARCHAR2(2000),
    http_status         NUMBER(3),
    response_time_ms    NUMBER(10),
    attempted_at        TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX idx_dispatches_event   ON event_dispatches (event_id);
CREATE INDEX idx_dispatches_vendor  ON event_dispatches (vendor_msg_id);
CREATE INDEX idx_dispatches_status  ON event_dispatches (status, attempted_at);

-- ───────────────────────────────────────────────────────────
-- EVENT_RETRIES
-- ───────────────────────────────────────────────────────────
CREATE TABLE event_retries (
    retry_id            NUMBER(19)      DEFAULT seq_retry_id.NEXTVAL PRIMARY KEY,
    event_id            NUMBER(19)      NOT NULL REFERENCES events(event_id),
    dispatch_id         NUMBER(19)      REFERENCES event_dispatches(dispatch_id),
    retry_number        NUMBER(3)       NOT NULL,
    error_code          VARCHAR2(20)    NOT NULL,
    next_attempt_at     TIMESTAMP       NOT NULL,
    status              VARCHAR2(20)    DEFAULT 'SCHEDULED'
        CHECK (status IN ('SCHEDULED','IN_PROGRESS','SUCCESS','EXHAUSTED','CANCELLED')),
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    completed_at        TIMESTAMP
);

CREATE INDEX idx_retries_event  ON event_retries (event_id);
CREATE INDEX idx_retries_next   ON event_retries (next_attempt_at, status);

-- ───────────────────────────────────────────────────────────
-- AUDIT_LOG (partitioned by month)
-- ───────────────────────────────────────────────────────────
CREATE TABLE audit_log (
    audit_id            NUMBER(19)      DEFAULT seq_audit_id.NEXTVAL PRIMARY KEY,
    correlation_id      VARCHAR2(64)    NOT NULL,
    event_id            NUMBER(19),
    service_name        VARCHAR2(64)    NOT NULL,
    stage               VARCHAR2(32)    NOT NULL,
    action              VARCHAR2(64)    NOT NULL,
    status              VARCHAR2(20)    NOT NULL,
    error_code          VARCHAR2(20),
    error_detail        VARCHAR2(2000),
    metadata            CLOB,
    actor               VARCHAR2(64)    DEFAULT 'SYSTEM',
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL
)
PARTITION BY RANGE (created_at)
INTERVAL (NUMTOYMINTERVAL(1, 'MONTH'))
(
    PARTITION p_initial VALUES LESS THAN (TIMESTAMP '2025-07-01 00:00:00')
);

CREATE INDEX idx_audit_corr     ON audit_log (correlation_id) LOCAL;
CREATE INDEX idx_audit_event    ON audit_log (event_id) LOCAL;
CREATE INDEX idx_audit_created  ON audit_log (created_at) LOCAL;

COMMENT ON TABLE audit_log IS 'Immutable audit trail. Partitioned monthly; retain 365 days active, 3 years archived.';

-- ───────────────────────────────────────────────────────────
-- VENDOR_CALLBACKS
-- ───────────────────────────────────────────────────────────
CREATE TABLE vendor_callbacks (
    callback_id         NUMBER(19)      DEFAULT seq_callback_id.NEXTVAL PRIMARY KEY,
    dispatch_id         NUMBER(19)      REFERENCES event_dispatches(dispatch_id),
    vendor_name         VARCHAR2(64)    NOT NULL,
    vendor_msg_id       VARCHAR2(256)   NOT NULL,
    callback_status     VARCHAR2(32)    NOT NULL,
    raw_payload         CLOB,
    received_at         TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX idx_callbacks_vendor    ON vendor_callbacks (vendor_name, vendor_msg_id);
CREATE INDEX idx_callbacks_dispatch  ON vendor_callbacks (dispatch_id);

-- ───────────────────────────────────────────────────────────
-- DEDUP_CACHE
-- ───────────────────────────────────────────────────────────
CREATE TABLE dedup_cache (
    dedup_key           VARCHAR2(256)   PRIMARY KEY,
    event_id            NUMBER(19),
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    expires_at          TIMESTAMP       NOT NULL
);

CREATE INDEX idx_dedup_expires ON dedup_cache (expires_at);

COMMENT ON TABLE dedup_cache IS 'Short-lived dedup entries. Purge daily where expires_at < SYSTIMESTAMP.';

-- ───────────────────────────────────────────────────────────
-- ARCHIVAL HELPER: Purge expired dedup entries
-- ───────────────────────────────────────────────────────────
CREATE OR REPLACE PROCEDURE purge_expired_dedup AS
BEGIN
    DELETE FROM dedup_cache WHERE expires_at < SYSTIMESTAMP;
    COMMIT;
END;
/

-- Schedule daily at 2 AM
BEGIN
    DBMS_SCHEDULER.CREATE_JOB (
        job_name        => 'JOB_PURGE_DEDUP',
        job_type        => 'STORED_PROCEDURE',
        job_action      => 'purge_expired_dedup',
        start_date      => SYSTIMESTAMP,
        repeat_interval => 'FREQ=DAILY;BYHOUR=2;BYMINUTE=0;BYSECOND=0',
        enabled         => TRUE,
        comments        => 'Purge expired deduplication cache entries'
    );
END;
/

-- ───────────────────────────────────────────────────────────
-- SEED DATA: Sample upstream configs
-- ───────────────────────────────────────────────────────────
INSERT INTO upstream_config (upstream_id, display_name, kafka_topic, processor_bean, default_channel, max_retries, priority, rate_limit_per_sec)
VALUES ('BANKING', 'Banking Alerts', 'inbound.banking', 'bankingProcessor', 'SMS', 3, 3, 5000);

INSERT INTO upstream_config (upstream_id, display_name, kafka_topic, processor_bean, default_channel, max_retries, priority, rate_limit_per_sec)
VALUES ('INSURANCE', 'Insurance Notifications', 'inbound.insurance', 'insuranceProcessor', 'EMAIL', 3, 5, 3000);

INSERT INTO upstream_config (upstream_id, display_name, kafka_topic, processor_bean, default_channel, max_retries, priority, rate_limit_per_sec)
VALUES ('OTP_AUTH', 'OTP Authentication', 'inbound.otp-auth', 'otpAuthProcessor', 'OTP', 1, 1, 5000);

INSERT INTO upstream_config (upstream_id, display_name, kafka_topic, processor_bean, default_channel, max_retries, priority, rate_limit_per_sec)
VALUES ('MARKETING', 'Marketing Campaigns', 'inbound.marketing', 'marketingProcessor', 'PUSH', 2, 7, 2000);

INSERT INTO upstream_config (upstream_id, display_name, kafka_topic, processor_bean, default_channel, max_retries, priority, rate_limit_per_sec)
VALUES ('LOGISTICS', 'Logistics Updates', 'inbound.logistics', 'logisticsProcessor', 'WEBHOOK', 3, 5, 3000);

INSERT INTO channel_vendor_config (channel, vendor_name, is_primary, priority, rate_limit_per_sec, endpoint_url, auth_type, timeout_ms)
VALUES ('SMS', 'TWILIO', 1, 1, 500, 'https://api.twilio.com/2010-04-01/Accounts/{sid}/Messages.json', 'BASIC', 5000);

INSERT INTO channel_vendor_config (channel, vendor_name, is_primary, priority, rate_limit_per_sec, endpoint_url, auth_type, timeout_ms)
VALUES ('SMS', 'MSG91', 0, 2, 300, 'https://api.msg91.com/api/v5/flow/', 'API_KEY', 5000);

INSERT INTO channel_vendor_config (channel, vendor_name, is_primary, priority, rate_limit_per_sec, endpoint_url, auth_type, timeout_ms)
VALUES ('EMAIL', 'SENDGRID', 1, 1, 1000, 'https://api.sendgrid.com/v3/mail/send', 'BEARER', 10000);

INSERT INTO channel_vendor_config (channel, vendor_name, is_primary, priority, rate_limit_per_sec, endpoint_url, auth_type, timeout_ms)
VALUES ('PUSH', 'FCM', 1, 1, 2000, 'https://fcm.googleapis.com/v1/projects/{project}/messages:send', 'OAUTH2', 5000);

COMMIT;
