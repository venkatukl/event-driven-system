# Event Processing Platform — Architecture & Design Document

## 1. System Overview

This platform ingests ~5,000 messages/second per upstream (5–6 upstreams, ~25,000–30,000 msg/s aggregate), validates, normalises, processes and dispatches notifications across SMS, Email, OTP, Voice, Webhooks, and Push channels via enterprise vendor APIs.

### 1.1 Design Principles

| Principle | Implementation |
|-----------|---------------|
| Configuration-driven | New upstream = new config block + business-logic bean. Zero framework changes. |
| Pluggable dispatch | Adapter pattern per channel; add new channels via Spring bean + config. |
| Exactly-once semantics | Kafka transactional producers + idempotent consumers + DB dedup table. |
| Resilient by default | Resilience4J circuit breakers, retry, bulkheads on every outbound call. |
| Observable | Structured JSON logs (Logback + MDC), Micrometer metrics, distributed trace-id. |
| Horizontally scalable | Stateless pods; scale each microservice independently via OCP HPA. |

---

## 2. Microservice Decomposition

### 2.1 Services (5 microservices)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        KAFKA CLUSTER                                     │
│  Topics: inbound.<upstream>, processing.events, dispatch.<channel>,      │
│          retry.events, dlt.events, audit.events                          │
└────┬──────────┬──────────────┬──────────────┬──────────────┬────────────┘
     │          │              │              │              │
     ▼          ▼              ▼              ▼              ▼
┌─────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│INGESTION│ │PROCESSING│ │DISPATCH  │ │  RETRY   │ │  AUDIT   │
│ SERVICE │ │ SERVICE  │ │ SERVICE  │ │ SERVICE  │ │ SERVICE  │
│         │ │          │ │          │ │          │ │          │
│• Receive│ │• Validate│ │• Route   │ │• Consume │ │• Consume │
│• Dedup  │ │• Normaliz│ │• Throttle│ │  retry   │ │  audit   │
│• Enrich │ │• Orchestr│ │• Dispatch│ │  topic   │ │  topic   │
│• Route  │ │• Persist │ │• Callback│ │• Exp.    │ │• Persist │
│         │ │          │ │          │ │  backoff │ │• Archive │
└─────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘
     │          │              │              │              │
     └──────────┴──────────────┴──────────────┴──────────────┘
                          ORACLE DB + REDIS
```

### 2.2 Bounded Contexts

| # | Service | Bounded Context | Responsibilities |
|---|---------|----------------|-----------------|
| 1 | **ingestion-service** | Inbound Gateway | Kafka consumer per upstream topic; deduplication (Redis bloom + DB); header enrichment; schema validation; publishes to `processing.events` |
| 2 | **processing-service** | Business Orchestration | Upstream-specific validation & normalisation (factory pattern); channel resolution; priority assignment; persists event + publishes to `dispatch.<channel>` |
| 3 | **dispatch-service** | Channel Delivery | Adapter per channel (SMS, Email, OTP, Voice, Webhook, Push); vendor API calls with circuit breaker + rate limiter; callback/status tracking; publishes failures to `retry.events` |
| 4 | **retry-service** | Failure Recovery | Consumes `retry.events`; exponential backoff with jitter; max-retry enforcement; publishes to DLT after exhaustion; re-publishes recoverable events to `dispatch.<channel>` |
| 5 | **audit-service** | Observability & Compliance | Consumes `audit.events`; persists audit trail; drives dashboards; handles data archival |

### 2.3 Shared Library: `platform-common`

A Maven module (not a service) containing:
- Error code enums and exception hierarchy
- Kafka serialiser/deserialiser (JSON + Avro)
- Base event model (EventEnvelope)
- Factory/adapter interfaces
- Spring auto-configuration for Resilience4J defaults

---

## 3. Kafka Architecture

### 3.1 Topic Design

| Topic Pattern | Partitions | Replication | Retention | Purpose |
|--------------|------------|-------------|-----------|---------|
| `inbound.<upstream>` | 12 per upstream | 3 | 7 days | Raw messages from each upstream |
| `processing.events` | 24 | 3 | 3 days | Validated, normalised events |
| `dispatch.sms` | 12 | 3 | 3 days | SMS delivery queue |
| `dispatch.email` | 12 | 3 | 3 days | Email delivery queue |
| `dispatch.otp` | 6 | 3 | 1 day | OTP (time-sensitive, fewer partitions, priority) |
| `dispatch.voice` | 6 | 3 | 3 days | Voice call queue |
| `dispatch.webhook` | 12 | 3 | 3 days | Webhook delivery queue |
| `dispatch.push` | 12 | 3 | 3 days | Push notification queue |
| `retry.events` | 12 | 3 | 7 days | Failed messages awaiting retry |
| `dlt.events` | 6 | 3 | 30 days | Dead letter (exhausted retries) |
| `audit.events` | 12 | 3 | 14 days | Audit trail events |

### 3.2 Partition Strategy (Avoiding Rebalancing Issues)

```yaml
# ── CRITICAL KAFKA CONFIGURATIONS ──

# 1. USE STATIC GROUP MEMBERSHIP to eliminate rebalancing on pod restarts
spring.kafka.consumer.properties:
  group.instance.id: ${HOSTNAME}          # OCP pod name = stable identity
  session.timeout.ms: 45000               # longer timeout; no rebalance on restart < 45s
  heartbeat.interval.ms: 10000

# 2. USE COOPERATIVE STICKY ASSIGNOR (incremental rebalance)
spring.kafka.consumer.properties:
  partition.assignment.strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor

# 3. CONCURRENCY = PARTITIONS for maximum parallelism without contention
spring.kafka.listener:
  concurrency: 12                          # match partition count
  type: batch                              # batch processing for throughput
  ack-mode: manual_immediate               # commit after processing

# 4. PRODUCER — idempotent + transactional
spring.kafka.producer:
  acks: all
  enable.idempotence: true
  transactional-id-prefix: ${spring.application.name}-tx-
  retries: 3
  batch-size: 65536
  linger-ms: 10
  compression-type: lz4
  buffer-memory: 67108864
```

### 3.3 Consumer Tuning for 5K msg/s per Upstream

```yaml
spring.kafka.consumer:
  max-poll-records: 500                    # batch size per poll
  fetch-min-bytes: 50000                   # wait for 50KB before returning
  fetch-max-wait-ms: 200                   # or 200ms, whichever first
  max-partition-fetch-bytes: 1048576       # 1MB per partition
  auto-offset-reset: earliest
  enable-auto-commit: false                # manual commit only
```

### 3.4 Why These Numbers?

- **12 partitions per upstream**: 5,000 msg/s ÷ 12 partitions ≈ 417 msg/s per partition — well within single-thread capacity.
- **Static group membership**: Pod restarts in OCP do NOT trigger rebalance as long as restart < `session.timeout.ms`.
- **CooperativeStickyAssignor**: Only moved partitions are revoked; other consumers continue processing.
- **Batch listener**: Amortises commit overhead; processes 500 records per poll.

---

## 4. Database Schema (Oracle)

### 4.1 Sequence & ID Strategy

```sql
-- ═══════════════════════════════════════════════════════════
-- WHY NUMBER(19)?
-- Java Long.MAX_VALUE = 9,223,372,036,854,775,807 (19 digits)
-- NUMBER(19) maps cleanly to Java long without overflow risk.
-- At 30,000 events/second → 946 billion/year → lasts ~9,700 years.
-- Oracle SEQUENCE with CACHE 1000 avoids contention under load.
-- ═══════════════════════════════════════════════════════════

-- Use CACHE 1000 for high-throughput sequences (reduces latch contention)
-- Use ORDER only if you need monotonic across RAC nodes (slower)
-- Use NOORDER (default) for performance when gap-free is not required

CREATE SEQUENCE seq_event_id      START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;
CREATE SEQUENCE seq_dispatch_id   START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;
CREATE SEQUENCE seq_retry_id      START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;
CREATE SEQUENCE seq_audit_id      START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;
CREATE SEQUENCE seq_callback_id   START WITH 1 INCREMENT BY 1 CACHE 1000 NOORDER;
```

### 4.2 Core Tables

```sql
-- ═══════════════════════════════════════════════════════════
-- TABLE: EVENTS (master event record)
-- ═══════════════════════════════════════════════════════════
CREATE TABLE events (
    event_id            NUMBER(19)      DEFAULT seq_event_id.NEXTVAL PRIMARY KEY,
    correlation_id      VARCHAR2(64)    NOT NULL,        -- trace across services
    upstream_id         VARCHAR2(32)    NOT NULL,        -- e.g., 'BANKING', 'INSURANCE'
    upstream_msg_id     VARCHAR2(128)   NOT NULL,        -- dedup key from upstream
    channel             VARCHAR2(20)    NOT NULL,        -- SMS, EMAIL, OTP, VOICE, WEBHOOK, PUSH
    priority            NUMBER(2)       DEFAULT 5,       -- 1=highest, 9=lowest
    recipient           VARCHAR2(256)   NOT NULL,        -- phone/email/device token/URL
    subject             VARCHAR2(512),
    payload             CLOB            NOT NULL,        -- normalised JSON payload
    template_id         VARCHAR2(64),                    -- optional template reference
    status              VARCHAR2(20)    DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED','VALIDATED','PROCESSING','DISPATCHED',
                          'DELIVERED','FAILED','RETRYING','DLT','EXPIRED')),
    error_code          VARCHAR2(20),                    -- from ErrorCode enum
    error_detail        VARCHAR2(2000),
    retry_count         NUMBER(3)       DEFAULT 0,
    max_retries         NUMBER(3)       DEFAULT 3,
    scheduled_at        TIMESTAMP,                       -- for deferred delivery
    expires_at          TIMESTAMP,                       -- TTL for OTP etc.
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    created_by          VARCHAR2(64)    DEFAULT 'SYSTEM',
    version             NUMBER(10)      DEFAULT 0        -- optimistic locking
);

-- Deduplication index (upstream + msg_id must be unique)
CREATE UNIQUE INDEX idx_events_dedup ON events (upstream_id, upstream_msg_id);

-- Query patterns
CREATE INDEX idx_events_status     ON events (status, created_at);
CREATE INDEX idx_events_corr       ON events (correlation_id);
CREATE INDEX idx_events_channel    ON events (channel, status);
CREATE INDEX idx_events_recipient  ON events (recipient, created_at);
CREATE INDEX idx_events_created    ON events (created_at) LOCAL;  -- for partitioning


-- ═══════════════════════════════════════════════════════════
-- TABLE: EVENT_DISPATCHES (one per delivery attempt)
-- ═══════════════════════════════════════════════════════════
CREATE TABLE event_dispatches (
    dispatch_id         NUMBER(19)      DEFAULT seq_dispatch_id.NEXTVAL PRIMARY KEY,
    event_id            NUMBER(19)      NOT NULL REFERENCES events(event_id),
    channel             VARCHAR2(20)    NOT NULL,
    vendor_name         VARCHAR2(64)    NOT NULL,        -- e.g., 'TWILIO', 'SENDGRID'
    vendor_msg_id       VARCHAR2(256),                   -- vendor's reference
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

CREATE INDEX idx_dispatches_event  ON event_dispatches (event_id);
CREATE INDEX idx_dispatches_vendor ON event_dispatches (vendor_msg_id);
CREATE INDEX idx_dispatches_status ON event_dispatches (status, attempted_at);


-- ═══════════════════════════════════════════════════════════
-- TABLE: EVENT_RETRIES (retry tracking)
-- ═══════════════════════════════════════════════════════════
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

CREATE INDEX idx_retries_event     ON event_retries (event_id);
CREATE INDEX idx_retries_next      ON event_retries (next_attempt_at, status);


-- ═══════════════════════════════════════════════════════════
-- TABLE: AUDIT_LOG (immutable audit trail)
-- ═══════════════════════════════════════════════════════════
CREATE TABLE audit_log (
    audit_id            NUMBER(19)      DEFAULT seq_audit_id.NEXTVAL PRIMARY KEY,
    correlation_id      VARCHAR2(64)    NOT NULL,
    event_id            NUMBER(19),
    service_name        VARCHAR2(64)    NOT NULL,
    stage               VARCHAR2(32)    NOT NULL,        -- INGEST, VALIDATE, PROCESS, DISPATCH, RETRY, DLT
    action              VARCHAR2(64)    NOT NULL,        -- e.g., 'DEDUP_CHECK', 'VENDOR_CALL'
    status              VARCHAR2(20)    NOT NULL,        -- SUCCESS, FAILURE, SKIPPED
    error_code          VARCHAR2(20),
    error_detail        VARCHAR2(2000),
    metadata            CLOB,                            -- JSON with contextual data
    actor               VARCHAR2(64)    DEFAULT 'SYSTEM',
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX idx_audit_corr        ON audit_log (correlation_id);
CREATE INDEX idx_audit_event       ON audit_log (event_id);
CREATE INDEX idx_audit_created     ON audit_log (created_at);


-- ═══════════════════════════════════════════════════════════
-- TABLE: VENDOR_CALLBACKS (async delivery receipts)
-- ═══════════════════════════════════════════════════════════
CREATE TABLE vendor_callbacks (
    callback_id         NUMBER(19)      DEFAULT seq_callback_id.NEXTVAL PRIMARY KEY,
    dispatch_id         NUMBER(19)      REFERENCES event_dispatches(dispatch_id),
    vendor_name         VARCHAR2(64)    NOT NULL,
    vendor_msg_id       VARCHAR2(256)   NOT NULL,
    callback_status     VARCHAR2(32)    NOT NULL,        -- vendor-specific status
    raw_payload         CLOB,                            -- raw vendor callback JSON
    received_at         TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX idx_callbacks_vendor  ON vendor_callbacks (vendor_name, vendor_msg_id);
CREATE INDEX idx_callbacks_dispatch ON vendor_callbacks (dispatch_id);


-- ═══════════════════════════════════════════════════════════
-- TABLE: UPSTREAM_CONFIG (configuration-driven upstream registry)
-- ═══════════════════════════════════════════════════════════
CREATE TABLE upstream_config (
    upstream_id         VARCHAR2(32)    PRIMARY KEY,
    display_name        VARCHAR2(128)   NOT NULL,
    kafka_topic         VARCHAR2(128)   NOT NULL,
    processor_bean      VARCHAR2(128)   NOT NULL,        -- Spring bean name
    default_channel     VARCHAR2(20),
    max_retries         NUMBER(3)       DEFAULT 3,
    priority            NUMBER(2)       DEFAULT 5,
    rate_limit_per_sec  NUMBER(10)      DEFAULT 1000,
    is_active           NUMBER(1)       DEFAULT 1,
    config_json         CLOB,                            -- extra config per upstream
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL
);


-- ═══════════════════════════════════════════════════════════
-- TABLE: CHANNEL_VENDOR_CONFIG (vendor routing per channel)
-- ═══════════════════════════════════════════════════════════
CREATE TABLE channel_vendor_config (
    channel             VARCHAR2(20)    NOT NULL,
    vendor_name         VARCHAR2(64)    NOT NULL,
    is_primary          NUMBER(1)       DEFAULT 1,       -- primary vs fallback
    priority            NUMBER(2)       DEFAULT 1,       -- vendor priority for failover
    rate_limit_per_sec  NUMBER(10)      DEFAULT 500,
    circuit_breaker_cfg VARCHAR2(512),                   -- JSON config
    endpoint_url        VARCHAR2(512)   NOT NULL,
    auth_type           VARCHAR2(20)    DEFAULT 'API_KEY',
    is_active           NUMBER(1)       DEFAULT 1,
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_channel_vendor PRIMARY KEY (channel, vendor_name)
);


-- ═══════════════════════════════════════════════════════════
-- TABLE: DEDUP_CACHE (idempotency - short-lived)
-- ═══════════════════════════════════════════════════════════
CREATE TABLE dedup_cache (
    dedup_key           VARCHAR2(256)   PRIMARY KEY,     -- hash(upstream_id + upstream_msg_id)
    event_id            NUMBER(19),
    created_at          TIMESTAMP       DEFAULT SYSTIMESTAMP NOT NULL,
    expires_at          TIMESTAMP       NOT NULL         -- auto-purge after TTL
);

CREATE INDEX idx_dedup_expires ON dedup_cache (expires_at);


-- ═══════════════════════════════════════════════════════════
-- PARTITIONING STRATEGY (for tables > 100M rows)
-- ═══════════════════════════════════════════════════════════
-- Partition EVENTS and AUDIT_LOG by RANGE on created_at (monthly)
-- Enables efficient archival: detach old partitions → move to archive tablespace

-- Example (apply to events, audit_log, event_dispatches):
-- ALTER TABLE events MODIFY PARTITION BY RANGE (created_at)
--   INTERVAL (NUMTOYMINTERVAL(1,'MONTH'))
--   (PARTITION p_initial VALUES LESS THAN (TIMESTAMP '2025-02-01 00:00:00'));


-- ═══════════════════════════════════════════════════════════
-- ARCHIVAL JOB (run via Oracle Scheduler or Spring @Scheduled)
-- ═══════════════════════════════════════════════════════════
-- 1. Detach partition older than retention period
-- 2. Export to archive tablespace or dump file
-- 3. Drop detached partition
-- Retention: events=90 days, audit_log=365 days, dispatches=90 days
```

### 4.3 Entity-Relationship Summary

```
upstream_config 1──────M events 1──────M event_dispatches 1──────M vendor_callbacks
                           │                    │
                           │                    │
                           M                    M
                      event_retries         (via dispatch_id)
                           │
                           └───── audit_log (via event_id + correlation_id)
```

---

## 5. Error Code Taxonomy

Every stage of processing uses a structured error code. Error codes are 3-part: `{STAGE}_{CATEGORY}_{SPECIFIC}`.

```
Stage Prefixes:
  ING = Ingestion
  VAL = Validation
  PRC = Processing
  DSP = Dispatch
  RTY = Retry
  SYS = System/Infra

Category:
  DEDUP = Deduplication
  SCHEMA = Schema validation
  TRANSFORM = Data transformation
  VENDOR = Vendor API
  TIMEOUT = Timeout
  CIRCUIT = Circuit breaker
  RATE = Rate limiting
  DB = Database
  KAFKA = Kafka
  AUTH = Authentication
  CONFIG = Configuration
```

### 5.1 Complete Error Code Enum

| Code | Description | Retryable? | Stage |
|------|-------------|-----------|-------|
| `ING_DEDUP_001` | Duplicate message detected | No | Ingestion |
| `ING_SCHEMA_001` | Invalid message schema / missing required fields | No | Ingestion |
| `ING_SCHEMA_002` | Unsupported upstream identifier | No | Ingestion |
| `ING_KAFKA_001` | Kafka deserialization failure | No | Ingestion |
| `VAL_SCHEMA_001` | Business validation failed (upstream-specific rules) | No | Validation |
| `VAL_SCHEMA_002` | Invalid recipient format (phone/email) | No | Validation |
| `VAL_SCHEMA_003` | Template not found or inactive | No | Validation |
| `VAL_SCHEMA_004` | Message expired (past expires_at) | No | Validation |
| `PRC_TRANSFORM_001` | Normalisation/transformation error | No | Processing |
| `PRC_CONFIG_001` | Missing upstream processor configuration | No | Processing |
| `PRC_DB_001` | Event persistence failure | Yes | Processing |
| `DSP_VENDOR_001` | Vendor API returned error (4xx) | Conditional | Dispatch |
| `DSP_VENDOR_002` | Vendor API returned error (5xx) | Yes | Dispatch |
| `DSP_VENDOR_003` | Vendor API returned unexpected response | Yes | Dispatch |
| `DSP_TIMEOUT_001` | Vendor API call timed out | Yes | Dispatch |
| `DSP_CIRCUIT_001` | Circuit breaker is open; vendor unavailable | Yes | Dispatch |
| `DSP_RATE_001` | Rate limit exceeded for channel/vendor | Yes | Dispatch |
| `DSP_AUTH_001` | Vendor authentication failed | No | Dispatch |
| `RTY_EXHAUSTED_001` | Max retries exceeded; moved to DLT | No | Retry |
| `RTY_EXPIRED_001` | Message expired during retry window | No | Retry |
| `SYS_DB_001` | Database connection failure | Yes | System |
| `SYS_DB_002` | Optimistic lock conflict (concurrent update) | Yes | System |
| `SYS_KAFKA_001` | Kafka producer send failure | Yes | System |
| `SYS_KAFKA_002` | Kafka transaction commit failure | Yes | System |
| `SYS_REDIS_001` | Redis connection failure (dedup cache fallback to DB) | Yes | System |
| `SYS_CONFIG_001` | Configuration load/refresh failure | No | System |

---

## 6. Message Flow (End-to-End)

```
Upstream → [inbound.<upstream> topic]
    │
    ▼
INGESTION SERVICE
    ├─ 1. Deserialise (Avro/JSON)
    ├─ 2. Dedup check (Redis bloom → DB fallback)
    ├─ 3. Schema validation (JSON Schema per upstream)
    ├─ 4. Enrich headers (correlation_id, trace_id, timestamp)
    ├─ 5. Publish audit event → [audit.events]
    └─ 6. Publish to → [processing.events]
           │
           ▼
PROCESSING SERVICE
    ├─ 1. Resolve upstream processor (UpstreamProcessorFactory)
    ├─ 2. Business validation (upstream-specific rules)
    ├─ 3. Normalise payload to canonical EventEnvelope
    ├─ 4. Resolve channel(s) — may fan-out to multiple channels
    ├─ 5. Assign priority (from upstream_config + business rules)
    ├─ 6. Persist to EVENTS table (transactional)
    ├─ 7. Publish audit event → [audit.events]
    └─ 8. Publish to → [dispatch.<channel>]
           │
           ▼
DISPATCH SERVICE
    ├─ 1. Resolve vendor adapter (ChannelAdapterFactory)
    ├─ 2. Rate limit check (Resilience4j RateLimiter, per vendor)
    ├─ 3. Circuit breaker check
    ├─ 4. Call vendor API (with timeout)
    ├─ 5. Persist EVENT_DISPATCHES record
    ├─ 6. On success → update EVENTS.status = DISPATCHED
    ├─ 7. On failure →
    │      ├─ Retryable? → publish to [retry.events]
    │      └─ Non-retryable? → update status = FAILED, publish audit
    └─ 8. Publish audit event → [audit.events]
           │
           ▼ (on failure)
RETRY SERVICE
    ├─ 1. Calculate next_attempt_at (exponential backoff + jitter)
    ├─ 2. Check retry_count < max_retries
    │      ├─ Under limit → persist EVENT_RETRIES, re-publish to [dispatch.<channel>]
    │      └─ Exhausted → publish to [dlt.events], update status = DLT
    ├─ 3. Publish audit event → [audit.events]
    └─ 4. DLT handler alerts ops team
           │
           ▼ (always)
AUDIT SERVICE
    ├─ 1. Consume [audit.events]
    ├─ 2. Batch persist to AUDIT_LOG
    ├─ 3. Emit metrics (Micrometer → Prometheus)
    └─ 4. Archive old partitions (scheduled job)
```

---

## 7. Architecture & Workflow Considerations (NFRs & Missing Items)

### 7.1 Non-Functional Requirements

| NFR | Target | Implementation |
|-----|--------|---------------|
| Throughput | 30,000 msg/s aggregate | 12 partitions × 12 consumers × batch=500 |
| Latency (P99) | < 2s ingestion-to-dispatch | Async pipeline; no synchronous inter-service calls |
| Availability | 99.95% | OCP multi-AZ; Kafka RF=3; Oracle RAC/Data Guard |
| Durability | Zero message loss | Kafka acks=all; transactional producer; manual offset commit |
| Recovery (RPO) | 0 messages | Kafka retains 7 days; DB has hourly RMAN backups |
| Recovery (RTO) | < 15 min | StatefulSet with PVCs; static group membership = no rebalance |

### 7.2 Items You Should Not Miss

**A. Idempotency & Exactly-Once**
- Redis Bloom filter as fast first-pass dedup (1% false-positive acceptable since DB is authoritative).
- `dedup_cache` table with unique constraint as authoritative dedup.
- Kafka transactional producers ensure atomic produce+commit.
- DB optimistic locking (`version` column) prevents lost updates.

**B. Backpressure & Throttling**
- `max.poll.records=500` + `max.poll.interval.ms=300000` prevents consumer from being kicked out.
- Resilience4J `RateLimiter` per vendor (configurable in `channel_vendor_config.rate_limit_per_sec`).
- Resilience4J `Bulkhead` limits concurrent vendor API calls per dispatch pod.
- If vendor returns 429, extract `Retry-After` header and schedule accordingly.

**C. Circuit Breaker Strategy**
```yaml
# Per-vendor circuit breaker config
resilience4j.circuitbreaker:
  instances:
    twilio-sms:
      sliding-window-size: 100
      failure-rate-threshold: 50
      wait-duration-in-open-state: 30s
      permitted-number-of-calls-in-half-open-state: 10
      slow-call-duration-threshold: 5s
      slow-call-rate-threshold: 80
```

**D. Data Archival**
- Oracle Interval Partitioning on `created_at` (monthly).
- Scheduled job detaches partitions older than retention:
  - `events`: 90 days active → archive tablespace → drop after 1 year.
  - `audit_log`: 365 days active → archive → drop after 3 years.
  - `dedup_cache`: daily purge where `expires_at < SYSTIMESTAMP`.

**E. Security**
- Kafka: SASL/SCRAM + TLS for broker communication.
- Oracle: TDE (Transparent Data Encryption) for at-rest encryption.
- PII fields (`recipient`, `payload`) encrypted at application level (AES-256-GCM).
- OCP NetworkPolicy restricts inter-service traffic.
- Secrets managed via OCP Secrets / HashiCorp Vault (not in ConfigMap).

**F. Monitoring & Alerting**
- Micrometer metrics exported to Prometheus:
  - `event.ingested.count` (by upstream)
  - `event.dispatched.count` (by channel, vendor)
  - `event.failed.count` (by error_code)
  - `event.retry.count` (by retry_number)
  - `event.dlt.count`
  - `kafka.consumer.lag` (by group, topic)
  - `vendor.api.latency` (p50, p95, p99 by vendor)
  - `circuit.breaker.state` (by vendor)
- Alerts: consumer lag > 10,000; DLT rate > 1%; circuit breaker open > 5 min; error rate > 5%.

**G. Graceful Shutdown**
```yaml
# application.yml
spring.lifecycle.timeout-per-shutdown-phase: 30s
server.shutdown: graceful
spring.kafka.listener.shutdown-timeout: 25s  # drain in-flight before pod kill
```
- OCP `terminationGracePeriodSeconds: 60` in Deployment.
- `WakeupException` handler in consumers to stop polling cleanly.

**H. Configuration Refresh (Zero Downtime)**
- `upstream_config` and `channel_vendor_config` cached in Redis with 60s TTL.
- Spring `@RefreshScope` + Actuator `/refresh` endpoint for ConfigMap changes.
- Adding a new upstream:
  1. Insert row in `upstream_config`.
  2. Deploy upstream processor Spring bean.
  3. Create Kafka topic `inbound.<new_upstream>`.
  4. Update ConfigMap → rolling restart picks up new consumer.

**I. Testing Strategy**
- Embedded Kafka (`spring-kafka-test`) for integration tests.
- Testcontainers for Oracle XE + Redis in CI.
- Contract tests for upstream message schemas.
- Chaos testing: kill pods during processing to verify no message loss.

**J. Observability**
- Logback with JSON encoder + MDC fields: `correlationId`, `upstreamId`, `channel`, `eventId`.
- OpenTelemetry trace propagation via Kafka headers.
- Log aggregation: Fluentd → Elasticsearch → Kibana (or Splunk on OCP).

---

## 8. Configuration Samples

See `config/` directory for complete `application.yml` files per service.

## 9. Project Structure

```
event-platform/
├── platform-common/               # shared library
│   └── src/main/java/com/platform/common/
│       ├── model/                  # EventEnvelope, ChannelType, etc.
│       ├── error/                  # ErrorCode enum, exceptions
│       ├── kafka/                  # serializers, headers
│       ├── factory/                # processor & adapter interfaces
│       └── config/                 # Resilience4J auto-config
├── ingestion-service/
├── processing-service/
├── dispatch-service/
├── retry-service/
├── audit-service/
├── schema/                         # SQL DDL scripts
├── config/                         # application.yml per service
└── docs/                           # this document
```
