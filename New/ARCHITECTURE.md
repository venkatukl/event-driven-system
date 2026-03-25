# Architecture — CBD Alerts Platform

> Canonical reference for all developers. Aligned with the approved high-level architecture diagram.

---

## 1. System overview

A scalable, event-driven notification platform that ingests alert messages from multiple upstream business systems via MQ, Kafka, and file-based channels. It applies upstream-specific business validation, enrichment, and normalization, then dispatches notifications through SMS, Voice, and other vendor APIs. The platform uses MongoDB for persistence, Redis for caching, and Apache Kafka for inter-service communication with end-to-end priority tiering.

### Key numbers

| Parameter | Value |
|-----------|-------|
| Upstreams | 10 (growing to 15+ over 2 years) |
| Daily volume | 5 million messages/day |
| Peak throughput | ~1,200 msg/s |
| Channels | SMS, Voice, other vendor integrations |
| Retention | 90 days (TTL-managed in MongoDB) |
| Availability | Active-active, multi-DC |

### Tech stack

| Component | Technology |
|-----------|------------|
| Language | Java 17, Gradle, Tomcat 10 |
| Framework | Spring Boot 3.2.x, Spring Kafka |
| Message broker | Apache Kafka (enterprise-managed, max 10 partitions/topic, RF=3) |
| Database | MongoDB (replica set, multi-DC) |
| Cache | Redis Cluster (geo-replicated) |
| Resilience | Resilience4J (circuit breaker, rate limiter, bulkhead) |
| Observability | Micrometer → Prometheus → Grafana, Splunk (logs) |
| Deployment | OpenShift (OCP), rolling updates, HPA |
| Archival | ZL Technologies (long-term) |
| API Gateway | Enterprise API gateway (for SOR and vendor access) |

---

## 2. Architecture layers

The system follows a top-down flow through 6 distinct layers:

```
Upstream Channels (MQ, Kafka, File)
    ↓
Ingestion Layer (adapter, schema validation, dedup, normalize)
    ↓
Kafka — processor.high / processor.medium / processor.low
    ↓
Processing Layer (pre-processor enrichment → upstream processor transformation → post-processor validation)
    ↓
Kafka — dispatch.high / dispatch.medium / dispatch.low
    ↓
Dispatch Layer (async pool → channel adapter → resilience patterns)
    ↓
Vendor Integrations (SMS, Voice, Other Vendor APIs) via API Gateway
```

With cross-cutting concerns:
- **Persistence Layer**: MongoDB (left side of diagram)
- **Cache Layer**: Redis — templates, config, user & account preferences (right side)
- **Internal SORs**: Entitlements, CMS, EW & Other SORs (accessed via API Gateway from processing layer)
- **Retry Service**: retry.events topic, retry config, re-dispatch loop back to dispatch topics
- **Audit Service**: audit.events topic, all services publish state transitions
- **Shared Services**: Platform common library, Observability (Splunk), Archival Job (ZL Technologies)

---

## 3. Microservices

| Service | Purpose | Pods | Key patterns |
|---------|---------|------|-------------|
| **ingestion-service** | Adapter for MQ/Kafka/File, schema validation, 3-tier dedup (Redis → MongoDB → vendor), normalize, assign priority | 2 × concurrency=5 | Adapter pattern, batch consumer |
| **processing-service** | Pre-processor (enrichment from SORs), upstream processor (transformation via factory), post-processor (validation), ACID write + outbox | 3 × concurrency=5 | Configurable pipeline, factory pattern, outbox pattern, bulk write |
| **dispatch-service** | Async thread pool, channel adapter (factory), Resilience4J per vendor/upstream | 3–5 (HPA) × concurrency=5 | 50 async threads, vendor idempotency key |
| **retry-service** | Exponential backoff with jitter, max-retry enforcement, DLT routing | 2 × concurrency=3 | Lease-based polling, backoff with jitter |
| **audit-service** | Lifecycle trail, metrics aggregation, archival coordination | 2 × concurrency=5 | Bulk insert, TTL-based retention, ZL archival |
| **platform-common** | Shared library (not a service) | — | Error codes, models, factories, Kafka config, metrics |

---

## 4. Kafka topology

**19 topics, 190 partitions total.** All topics: 10 partitions, RF=3, CooperativeStickyAssignor, static group membership.

### Inbound (10 topics)
```
inbound.banking, inbound.insurance, inbound.cards, inbound.loans, inbound.{+6 more}
```
- **Producer**: Upstream systems (via MQ/Kafka/File adapter)
- **Consumer**: ingestion-service (2 pods × c=5 = 10 threads = 1 per partition)
- **Settings**: batch 500/poll, 100ms poll, manual ack, offset earliest

### Processing (3 topics — priority-tiered)
```
processor.high    → 8 consumer threads, 50ms poll
processor.medium  → 5 consumer threads, 100ms poll
processor.low     → 2 consumer threads, 200ms poll
```
- **Producer**: ingestion-service (routes by priority from upstream_config)
- **Consumer**: processing-service (3 pods × c=5 = 15 total threads)
- **Settings**: batch 500/poll, manual ack
- **Key**: event_id (even distribution)

### Dispatch (3 topics — priority-tiered)
```
dispatch.high     → 5 consumer threads, 50ms poll
dispatch.medium   → 3 consumer threads, 100ms poll
dispatch.low      → 2 consumer threads, 200ms poll
```
- **Producer**: outbox poller (processing-service) + retry-service (re-dispatch)
- **Consumer**: dispatch-service (3–5 pods HPA × c=5)
- **Settings**: batch 100/poll, manual ack
- **Note**: Channel is in payload, not topic name. Single deployment consumes all 3 topics with separate consumer groups per tier.

### Retry (1 topic)
```
retry.events
```
- **Producer**: dispatch-service (on vendor failure)
- **Consumer**: retry-service (2 pods × c=3)
- **Settings**: batch 100/poll, 500ms poll

### Audit (1 topic)
```
audit.events
```
- **Producer**: All 4 services publish state transitions
- **Consumer**: audit-service (2 pods × c=5)
- **Settings**: batch 1000/poll, 200ms poll

### Dead letter (1 topic)
```
dlt.events
```
- **Producer**: Ingestion/processing error handlers, retry-service (exhausted)
- **Consumer**: audit-service (tracking + alerting)

---

## 5. Upstream channels and adapter pattern

The ingestion layer supports 3 upstream channel types via the adapter pattern:

| Channel | Adapter | Description |
|---------|---------|-------------|
| **MQ** | `MqInboundAdapter` | Consumes from IBM MQ / ActiveMQ queues, converts to EventEnvelope |
| **Kafka** | `KafkaInboundAdapter` | Consumes from inbound.{upstream} topics directly |
| **File** | `FileInboundAdapter` | Watches SFTP/NFS directories, parses CSV/XML/JSON files, emits per-record events |

All adapters normalize to a common `EventEnvelope` before passing through the ingestion pipeline (schema validation → dedup → normalize → assign priority → publish to processor.{priority}).

---

## 6. Processing layer — configurable pipeline

The processing layer uses a **configuration-driven step pipeline**. Each upstream's processing is defined by database configuration, not code.

### Pipeline flow
```
Pre-Processor Pipeline (enrichment)
    → Upstream Processor (transformation)
    → Post-Processor Pipeline (validation)
    → ACID write to MongoDB (event document with embedded outbox)
    → Outbox poller publishes to dispatch.{priority}
```

### Pre-processor pipeline (enrichment)
Reusable steps configured per upstream. Calls internal SORs via API Gateway:
- `CustomerProfileEnricher` — fetches from Entitlements service
- `TemplateResolver` — resolves templates from CMS
- `AccountPreferenceEnricher` — fetches user & account preferences from Redis cache
- `StaticDefaultsEnricher` — injects defaults (priority, TTL)

### Upstream processor (transformation)
Factory pattern: `ConfigurableUpstreamProcessor` (zero-code, DB config only) or custom `UpstreamProcessor` implementations for complex business logic. Handles field mapping, format conversion, business rule application.

### Post-processor pipeline (validation)
- `RequiredFieldsValidator` — validates all required fields present after transformation
- `FieldFormatValidator` — regex validation on normalized fields
- `PriorityOverrideStep` — event-type level priority overrides
- `ChannelRouter` — determines dispatch channel from event type
- `DispatchTopicResolver` — resolves target dispatch.{priority} topic

### Configuration storage
Pipeline steps stored in `pipelineConfig` MongoDB collection. Onboarding a new simple upstream = insert config rows, no code deployment.

---

## 7. Dispatch layer

### Async thread pool
Each dispatch pod runs a 50-thread async pool (core=50, max=50, queue=500). Consumer threads hand off to the async pool immediately, keeping Kafka consumers responsive.

### Channel adapter (factory pattern)
`ChannelAdapterFactory` resolves the correct adapter based on `envelope.channel`:
- `SmsChannelAdapter` → SMS vendor API via API Gateway
- `VoiceChannelAdapter` → Voice vendor API via API Gateway
- Additional vendor adapters as needed

All adapters pass `event_id` as the vendor idempotency key (3rd tier of dedup guarantee).

### Resilience patterns (Resilience4J)
| Pattern | Scope | Configuration |
|---------|-------|---------------|
| CircuitBreaker | Per vendor | failureRate=50%, waitOpen=60s, slidingWindow=20 |
| RateLimiter | Per vendor | limitForPeriod=per vendor SLA, refreshPeriod=1s |
| Bulkhead | Per upstream | maxConcurrentCalls=10, maxWaitDuration=2s |

### API Gateway
Dispatch layer accesses vendor APIs through the enterprise API Gateway, which handles TLS termination, mutual auth, request logging, and vendor-specific protocol adaptation.

---

## 8. Persistence layer — MongoDB

### Collections (9 total)

| Collection | Purpose | Key indexes |
|------------|---------|-------------|
| `events` | Master event document (payload + outbox embedded) | status+createdAt, upstreamId+status, outboxStatus (partial), TTL 90d |
| `eventDispatches` | Append-only dispatch attempt history | eventId+attemptNumber, TTL 90d |
| `eventRetries` | Active retry tracking | nextRetryAt, TTL 90d |
| `dedupLog` | Dedup tier 2 (unique key) | dedupKey (unique), TTL 90d |
| `auditLog` | Full lifecycle trail | eventId+timestamp, TTL 90d |
| `upstreamMetrics` | Hourly aggregated metrics | upstreamId+channel+metricHour (unique) |
| `upstreamConfig` | Upstream registration & config | _id = upstreamId |
| `channelVendorConfig` | Vendor details per channel | channel+vendorId |
| `pipelineConfig` | Configurable processing steps | upstreamId+phase+stepOrder (unique) |

### Key design decisions
- **No hot/cold split**: MongoDB handles mixed document sizes efficiently. Event payload embedded in the event document.
- **Embedded outbox**: `outboxStatus` field on the event document replaces a separate outbox table. Poller uses lease-based `findOneAndUpdate` instead of `SELECT FOR UPDATE SKIP LOCKED`.
- **TTL indexes**: All operational collections use TTL indexes for automatic 90-day expiry. No manual partition archival needed.
- **Bulk writes**: `bulkWrite()` with unordered operations for throughput.
- **Atomic $inc**: Metrics aggregation uses `$inc` upsert — no race conditions.

---

## 9. Cache layer — Redis

Redis Cluster (geo-replicated across DCs) caches:

| Cache | Purpose | TTL |
|-------|---------|-----|
| Dedup keys | Tier 1 dedup (fast path, 99.9% hit rate) | 90 days |
| Templates & config | Upstream config, pipeline config, channel vendor config | 5 min |
| User & account preferences | Customer channel preferences, language, notification opt-outs | 15 min |

---

## 10. Internal SORs (via API Gateway)

The processing layer enriches events by calling internal Systems of Record through the enterprise API Gateway:

| SOR | Data provided | Called by |
|-----|---------------|----------|
| **Entitlements** | Customer profile, segment, permissions | Pre-processor `CustomerProfileEnricher` |
| **CMS** | Notification templates, content | Pre-processor `TemplateResolver` |
| **EW & Other SORs** | Account details, transaction context | Upstream-specific processors |

All SOR calls are wrapped in Resilience4J CircuitBreakers to prevent SOR failures from cascading into the notification pipeline.

---

## 11. Retry service

Dispatch failures (vendor 5xx, timeout, rate limit) flow to `retry.events` topic.

| Parameter | Value |
|-----------|-------|
| Backoff schedule | 30s → 1m → 5m → 15m |
| Max attempts | 4 |
| Jitter | 0–25% |
| On exhaustion | Publish to `dlt.events` |
| Re-dispatch | Publishes back to `dispatch.{priority}` |

DB: `eventRetries` collection — upsert attempt count, next_retry_at, backoff state.

---

## 12. Audit service

All services publish state transitions to `audit.events` topic. The audit service is the sole consumer.

- Bulk insert to `auditLog` collection (1000/batch)
- Metrics aggregation to `upstreamMetrics` collection (atomic `$inc` upsert)
- 90-day TTL auto-expiry
- Archival to ZL Technologies for long-term retention
- Also consumes `dlt.events` for failure tracking and alerting

---

## 13. Failure handling — per layer

| Layer | Transient failure | Non-retryable failure |
|-------|-------------------|----------------------|
| Ingestion | Spring Kafka in-place retry (3 attempts, 1s/2s/4s backoff) | Direct to `dlt.events` |
| Processing | Spring Kafka in-place retry + R4J CircuitBreaker on MongoDB | Direct to `dlt.events` |
| Dispatch | External retry service (30s → 15m backoff via retry.events) | `dlt.events` after 4 attempts |

Error codes: 25+ structured codes with prefix (ING/VAL/PRC/DSP/RTY/SYS) and retryable flag.

---

## 14. Three-tier deduplication

| Tier | Where | Purpose |
|------|-------|---------|
| 1 | Redis | Fast path, 99.9% hit rate. Eventually consistent cross-DC. |
| 2 | MongoDB | Durable fallback. Unique index on `dedupKey`. Catches cross-DC duplicates after replication. |
| 3 | Vendor idempotency | `event_id` passed as vendor idempotency key. Final safety net for active-active. |

---

## 15. Observability

| Layer | Tool | Purpose |
|-------|------|---------|
| Metrics | Micrometer → Prometheus → Grafana | 17 custom business metrics + JVM/Kafka/connection pool auto-exposed |
| Logs | Structured JSON → Splunk | Log investigation, correlation via MDC |
| SLA | MongoDB `upstreamMetrics` collection | Hourly aggregated metrics for SLA reporting |
| Alerting | Grafana alert rules | Consumer lag, error rate, circuit breaker state |

---

## 16. Deployment — OpenShift

| Service | Pods | HPA | Consumer groups per pod |
|---------|------|-----|------------------------|
| ingestion-service | 2 | No | 1 (inbound topics) |
| processing-service | 3 | No | 3 (processor.high/med/low) |
| dispatch-service | 3–5 | Yes (CPU + consumer lag) | 3 (dispatch.high/med/low) |
| retry-service | 2 | No | 1 (retry.events) |
| audit-service | 2 | No | 2 (audit.events + dlt.events) |

Single Deployment per service. Priority differentiation via thread allocation inside each pod, not pod-per-topic. Separate consumer group IDs per priority tier (`{service}-high`, `{service}-medium`, `{service}-low`).

---

## 17. Active-active / multi-DC

- **MongoDB**: Replica set across DCs, `w: "majority"` write concern
- **Redis**: Geo-replicated cluster
- **Kafka**: Stretched cluster or MirrorMaker 2 (enterprise-managed)
- **Dedup**: 3-tier model ensures zero duplicate dispatch even during DC failover
- **Connection strings**: Multi-host with automatic failover
- **Graceful shutdown**: `terminationGracePeriodSeconds` tuned to drain async thread pool

---

## 18. Shared services

| Service | Purpose |
|---------|---------|
| **Platform** (platform-common) | Shared library: error codes, models, factories, Kafka config, Micrometer metrics |
| **Observability** | Micrometer + Prometheus + Grafana (real-time), Splunk (structured logs) |
| **Archival Job** | Coordinates long-term archival to ZL Technologies beyond 90-day TTL window |
