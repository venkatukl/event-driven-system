# CBD Alerts Platform — Context Summary for Chat Continuation

> Paste this at the start of a new chat to resume from where we left off.
> Attach specific files from the project when asking about specific topics.

---

## What we're building

A scalable, event-driven notification platform (CBD Alerts) that ingests messages from multiple upstream business systems via MQ, Kafka, and file-based channels. It applies upstream-specific business validation, enrichment, and normalization through configurable pipelines, and dispatches notifications through SMS, Voice, and other vendor APIs via an enterprise API Gateway.

## Key numbers

| Parameter | Value |
|-----------|-------|
| Upstreams | 10 (growing to 15+) |
| Daily volume | 5M messages/day |
| Peak throughput | ~1,200 msg/s |
| Channels | SMS, Voice, other vendor integrations |
| Kafka topics | 19 topics, 190 partitions |
| Retention | 90 days (TTL-managed in MongoDB) |
| Availability | Active-active, multi-DC |

## Tech stack

- **Language**: Java 17, Gradle, Spring Boot 3.2.x, Spring Kafka
- **Database**: MongoDB (replica set, multi-DC) — NOT Oracle
- **Cache**: Redis Cluster (geo-replicated) — templates, config, user & account preferences
- **Broker**: Apache Kafka (enterprise-managed, max 10 partitions/topic, RF=3)
- **Resilience**: Resilience4J (CB per vendor, RL per vendor, BH per upstream)
- **Observability**: Micrometer → Prometheus → Grafana, Splunk (logs)
- **Deployment**: OpenShift (OCP), HPA on dispatch service
- **API Gateway**: Enterprise gateway for SOR and vendor access
- **Archival**: ZL Technologies (long-term beyond 90-day TTL)

## Architecture (5 microservices + shared library)

```
Upstream (MQ/Kafka/File) → Kafka inbound.{upstream} (10 topics)
  → INGESTION (adapter, schema validate, 3-tier dedup, normalize, assign priority)
  → Kafka processor.{high|medium|low} (3 topics)
  → PROCESSING (pre-processor enrichment → upstream processor transformation → post-processor validation → MongoDB + outbox)
  → Kafka dispatch.{high|medium|low} (3 topics)
  → DISPATCH (async 50 threads → channel adapter → R4J → vendor API via API Gateway)
  → Kafka retry.events → RETRY (backoff 30s→15m, re-dispatch or dlt.events)
  → Kafka audit.events → AUDIT (bulk insert, metrics, ZL archival)
```

## MongoDB collections (9)

| Collection | Purpose |
|------------|---------|
| `events` | Master doc: payload + outbox embedded. Lease-based poller. TTL 90d. |
| `eventDispatches` | Append-only dispatch attempts. TTL 90d. |
| `eventRetries` | Active retry tracking. TTL 90d. |
| `dedupLog` | Dedup tier 2. Unique index on dedupKey. TTL 90d. |
| `auditLog` | Full lifecycle trail. Bulk insert 1000/batch. TTL 90d. |
| `upstreamMetrics` | Hourly aggregated. Atomic $inc upsert. |
| `upstreamConfig` | Upstream registration & config. |
| `channelVendorConfig` | Vendor details per channel. |
| `pipelineConfig` | Configurable processing steps per upstream. |

## Key design decisions

1. **Adapter pattern**: MQ, Kafka, File sources normalized to EventEnvelope at ingestion
2. **End-to-end priority**: HIGH/MEDIUM/LOW from processing through dispatch, thread allocation per tier
3. **Configurable pipeline**: Pre/post processing steps are config-driven (pipelineConfig collection), no code for simple upstream onboarding
4. **Embedded outbox**: outboxStatus field on event document, lease-based findOneAndUpdate poller (no SKIP LOCKED in MongoDB)
5. **3-tier dedup**: Redis (fast) → MongoDB (durable) → vendor idempotency (final safety net)
6. **Single deployment per service**: All priority tiers consumed by same pod via separate consumer groups and thread allocation
7. **API Gateway**: Processing → SORs and Dispatch → vendors both go through enterprise gateway
8. **TTL auto-expiry**: No manual archival jobs for 90-day retention. ZL Technologies for long-term.
9. **19 simplified topics**: 3 dispatch topics (channel in payload, not topic name), single dlt.events

## What to attach in new chat based on topic

| If asking about... | Attach these files |
|--------------------|--------------------|
| Overall architecture | ARCHITECTURE.md |
| Copilot implementation | COPILOT-DEV-GUIDE.md + copilot-instructions.md |
| Kafka tuning | ARCHITECTURE.md (section 4) |
| MongoDB collections | ARCHITECTURE.md (section 8) |
| Processing pipeline | ARCHITECTURE.md (section 6) |
| Resilience / retry | ARCHITECTURE.md (sections 7, 11, 13) |
| Priority routing | ARCHITECTURE.md (sections 4, 6, 7) |
| Deployment / OCP | ARCHITECTURE.md (section 16) |
| Active-active / DR | ARCHITECTURE.md (section 17) |
