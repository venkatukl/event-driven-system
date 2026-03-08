-- ═══════════════════════════════════════════════════════════
-- V3: Upstream Metering + Quota Management
-- Per-upstream hourly metrics for SLA tracking and quota enforcement
-- ═══════════════════════════════════════════════════════════

CREATE TABLE upstream_metrics_hourly (
    upstream_id     VARCHAR2(32)   NOT NULL,
    hour_bucket     TIMESTAMP      NOT NULL,
    msgs_received   NUMBER(19)     DEFAULT 0,
    msgs_validated  NUMBER(19)     DEFAULT 0,
    msgs_dispatched NUMBER(19)     DEFAULT 0,
    msgs_failed     NUMBER(19)     DEFAULT 0,
    msgs_dlt        NUMBER(19)     DEFAULT 0,
    msgs_expired    NUMBER(19)     DEFAULT 0,
    avg_latency_ms  NUMBER(10)     DEFAULT 0,
    p99_latency_ms  NUMBER(10)     DEFAULT 0,
    error_breakdown CLOB,                       -- JSON: {"DSP_VENDOR_002": 15, "DSP_TIMEOUT_001": 3}
    created_at      TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at      TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_upstream_metrics PRIMARY KEY (upstream_id, hour_bucket)
);

CREATE INDEX idx_metrics_hour ON upstream_metrics_hourly (hour_bucket);

COMMENT ON TABLE upstream_metrics_hourly IS
    'Hourly aggregated metrics per upstream. Populated by audit-service from audit.events. '
    || 'Used for SLA dashboards, quota enforcement, and upstream health monitoring.';


-- Add quota columns to upstream_config
ALTER TABLE upstream_config ADD (
    hourly_quota        NUMBER(19)  DEFAULT 0,      -- 0 = unlimited
    daily_quota         NUMBER(19)  DEFAULT 0,      -- 0 = unlimited
    burst_limit_per_sec NUMBER(10)  DEFAULT 0,      -- 0 = use rate_limit_per_sec
    quota_action        VARCHAR2(20) DEFAULT 'DEPRIORITISE'
        CHECK (quota_action IN ('REJECT', 'DEPRIORITISE', 'BUFFER', 'ALERT_ONLY'))
);

COMMENT ON COLUMN upstream_config.quota_action IS
    'Action when quota exceeded: '
    || 'REJECT = return error to upstream, '
    || 'DEPRIORITISE = set priority=9 and process in background, '
    || 'BUFFER = pause consumer and resume when quota resets, '
    || 'ALERT_ONLY = process normally but alert ops team';


-- Daily summary view for SLA reporting
CREATE OR REPLACE VIEW v_upstream_daily_sla AS
SELECT
    upstream_id,
    TRUNC(hour_bucket) AS day,
    SUM(msgs_received) AS total_received,
    SUM(msgs_dispatched) AS total_dispatched,
    SUM(msgs_failed) AS total_failed,
    SUM(msgs_dlt) AS total_dlt,
    ROUND(SUM(msgs_dispatched) * 100.0 / NULLIF(SUM(msgs_received), 0), 2) AS delivery_rate_pct,
    ROUND(AVG(avg_latency_ms), 0) AS avg_latency_ms,
    MAX(p99_latency_ms) AS peak_p99_latency_ms
FROM upstream_metrics_hourly
GROUP BY upstream_id, TRUNC(hour_bucket);

COMMENT ON VIEW v_upstream_daily_sla IS
    'Daily SLA summary per upstream. Use for executive dashboards and SLA reviews.';


-- Retention: keep hourly metrics for 90 days, archive beyond
-- (Align with events table partitioning lifecycle)
