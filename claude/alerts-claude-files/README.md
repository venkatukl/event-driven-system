# Event Processing Platform

A scalable, resilient event-processing system handling ~30,000 messages/second across 5–6 upstream channels, with dispatch to SMS, Email, OTP, Voice, Webhooks, and Push Notifications.

## Architecture Overview

```
Upstreams → Kafka → Ingestion → Processing → Dispatch → Vendor APIs
                                                ↓
                                          Retry / DLT
                                                ↓
                                             Audit
```

**5 Microservices:**

| Service | Port | Purpose |
|---------|------|---------|
| ingestion-service | 8081 | Dedup, schema validation, enrichment |
| processing-service | 8082 | Business logic (per-upstream), normalisation, persistence |
| dispatch-service | 8083 | Vendor API calls with circuit breaker + rate limiter |
| retry-service | 8084 | Exponential backoff, DLT routing |
| audit-service | 8085 | Audit trail, metrics, archival |

**Shared Library:** `platform-common` — error codes, models, factories, Kafka config.

## Project Structure

```
event-platform/
├── docs/
│   └── ARCHITECTURE.md          ← Full architecture document
├── schema/
│   └── V1__init_schema.sql      ← Oracle DDL (tables, sequences, indexes, partitions)
├── config/
│   ├── create-kafka-topics.sh   ← Topic creation script
│   ├── logback-spring.xml       ← Structured JSON logging
│   └── ocp/                     ← OpenShift deployment manifests
│       ├── configmap.yml
│       └── ingestion-deployment.yml
├── common/                      ← Shared library (platform-common)
│   └── src/main/java/com/platform/common/
│       ├── model/               ← EventEnvelope, ChannelType, EventStatus
│       ├── error/               ← ErrorCode enum, exception hierarchy
│       ├── kafka/               ← AuditEventPublisher
│       ├── factory/             ← UpstreamProcessor, ChannelAdapter, factories
│       └── config/              ← KafkaConsumerConfig
└── services/
    ├── ingestion-service/       ← Kafka listener, dedup, schema validation
    ├── processing-service/      ← Upstream processors, event persistence
    ├── dispatch-service/        ← Channel adapters, Resilience4J integration
    ├── retry-service/           ← Exponential backoff, DLT
    └── audit-service/           ← Audit persistence, archival
```

## Quick Start

1. **Run DDL**: Execute `schema/V1__init_schema.sql` against Oracle
2. **Create topics**: Run `config/create-kafka-topics.sh`
3. **Build**: `mvn clean install` from root
4. **Deploy**: Apply OCP manifests from `config/ocp/`

## Adding a New Upstream

1. Implement `UpstreamProcessor` as a `@Component` (see `BankingUpstreamProcessor` as example)
2. Insert a row in `upstream_config` table
3. Create Kafka topic `inbound.<upstream_name>`
4. Deploy — no factory or framework changes needed

## Adding a New Dispatch Channel

1. Implement `ChannelAdapter` as a `@Component` (see `SmsChannelAdapter` as example)
2. Add the channel to `ChannelType` enum
3. Insert vendor config in `channel_vendor_config` table
4. Add Resilience4J config in `dispatch-service/application.yml`
5. Create Kafka topic `dispatch.<channel>`

## Key Design Decisions

- **Static group membership** prevents Kafka rebalancing on pod restarts
- **CooperativeStickyAssignor** enables incremental rebalance
- **NUMBER(19)** for IDs maps to Java `long`; lasts ~9,700 years at 30K/s
- **Sequence CACHE 1000** reduces Oracle latch contention under load
- **Two-tier dedup** (Redis fast path + DB authoritative) ensures exactly-once
- **Batch listeners** amortise Kafka commit overhead
- **Oracle Interval Partitioning** enables efficient monthly archival

## Documentation

See `docs/ARCHITECTURE.md` for the complete design including:
- Kafka topic design and tuning parameters
- Database schema with partitioning strategy
- Error code taxonomy (25+ codes across all stages)
- End-to-end message flow
- NFR targets and implementation
- Security, monitoring, archival, and testing strategies
