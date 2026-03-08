# Architecture Addendum — Revised Decisions

## Q1. Enterprise Kafka Limit: Max 10 Partitions Per Topic

### Revised Topic Design

| Topic | Partitions | Rationale |
|-------|-----------|-----------|
| `inbound.<upstream>` | **10** | Max allowed; 5K/s ÷ 10 = 500 msg/s per partition (fine) |
| `processing.events` | **10** | Aggregated from all upstreams; 30K/s ÷ 10 = 3K/s per partition |
| `dispatch.sms` | **10** | High-volume channel |
| `dispatch.email` | **10** | High-volume channel |
| `dispatch.otp` | **6** | Lower volume, time-critical |
| `dispatch.voice` | **4** | Lowest volume (voice calls are expensive) |
| `dispatch.webhook` | **10** | High-volume |
| `dispatch.push` | **10** | High-volume |
| `retry.events` | **6** | Retry volume is fraction of main flow |
| `dlt.events` | **4** | Very low volume (< 1% of total) |
| `audit.events` | **10** | Mirrors main throughput |

**Impact on throughput**: At 10 partitions with `processing.events` handling 30K/s aggregate, each partition processes ~3K/s. A single consumer thread can handle this if processing is fast (< 0.3ms per record). For dispatch (where vendor calls take 50–500ms), we compensate with **async thread pools** within each consumer thread (see Q3).

---

## Q2. Concurrency=N vs Concurrency=1 + Scale Pods

### Why Not concurrency=1 + 10 Pods?

| Factor | concurrency=1 × 10 pods | concurrency=5 × 2 pods |
|--------|------------------------|------------------------|
| **JVM overhead** | 10 × ~400MB base = 4GB wasted | 2 × ~400MB = 800MB |
| **DB pool connections** | 10 × 20 = 200 connections | 2 × 20 = 40 connections |
| **Redis connections** | 10 × 16 = 160 | 2 × 16 = 32 |
| **OCP resource cost** | 10 pods × 3GB = 30GB memory | 2 pods × 3GB = 6GB memory |
| **Health checks / monitoring** | 10 endpoints | 2 endpoints |
| **Deployment time** | 10 rolling restarts | 2 rolling restarts |
| **Partition assignment** | 1 partition/pod (simple) | 5 partitions/pod (efficient) |

### Recommended Configuration

```yaml
# For 10-partition topics:
# Option A (Production): 2 pods × concurrency=5 = 10 consumer threads
# Option B (HA):         3 pods × concurrency=4 = 12 threads (2 idle, instant failover)

spring.kafka.listener.concurrency: 5   # threads per pod
# Deploy 2 pods (or 3 for HA)
```

**Rule of thumb**: `pods × concurrency >= partition count`. Any excess threads sit idle (Kafka won't assign more partitions than exist). Having 1–2 extra threads provides instant failover if a pod dies — the surviving pod picks up the orphaned partitions within `session.timeout.ms`.

---

## Q3. Async Thread Pool for Processing

### The Problem

In batch mode, the consumer thread calls `poll()`, gets 500 records, and processes them sequentially. If dispatch involves a vendor API call averaging 200ms, processing 500 records = 100 seconds. This exceeds `max.poll.interval.ms` and the consumer gets kicked from the group.

### The Solution: Async Processing Within Batch

```
Consumer Thread (1 of 5 per pod):
  ┌─────────────────────────────────────────────────────┐
  │ poll() → 500 records                                │
  │                                                     │
  │ Submit all 500 to ThreadPoolTaskExecutor             │
  │ → 50 threads process in parallel                    │
  │ → CompletableFuture.allOf(...).join()               │
  │ → Wait for all to complete                          │
  │                                                     │
  │ Batch DB writes (single JDBC batch INSERT)          │
  │ ack.acknowledge()                                   │
  └─────────────────────────────────────────────────────┘
  
  500 records ÷ 50 threads = 10 records per thread
  10 records × 200ms each = 2 seconds total wall-clock
  (vs 100 seconds sequentially)
```

### Memory Budget (8 cores, 3GB per pod)

```
JVM Base Heap:                     ~400MB
Spring Context + Libraries:        ~200MB
Kafka Consumer Buffers (5 threads): ~50MB  (max.partition.fetch.bytes × partitions)
HikariCP Pool (20 conns):          ~40MB
Redis Pool (16 conns):             ~10MB
─────────────────────────────────────────
Baseline:                          ~700MB

Thread Pool (50 platform threads):
  Stack per thread:                512KB  (configure via -Xss512k)
  50 × 512KB =                     25MB

Working memory (500 records in-flight):
  ~2KB per EventEnvelope × 500 =    1MB
  JSON serialisation buffers:       ~10MB
─────────────────────────────────────────
Total:                             ~736MB

Remaining for GC headroom:         ~2.2GB  ✅ Safe
```

**Recommendation**: 50 platform threads with `-Xss512k` is safe. Do NOT use 200 threads — that's 100MB just for stacks, and with 8 cores you get no parallelism benefit beyond ~50 threads (CPU-bound at that point). The threads are mostly waiting on I/O (vendor HTTP calls), so 50 threads on 8 cores is optimal.

**Alternative**: On JDK 21+, use virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`). Virtual threads have ~1KB stack, so you could safely run 500 concurrent virtual threads. Spring Boot 3.2+ supports `spring.threads.virtual.enabled=true`.

---

## Q4. Microservice Count: 5 vs 3

### The Case for 3 Services (Recommended for Most Teams)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        KAFKA CLUSTER                                 │
└────┬────────────────────────┬────────────────────────┬──────────────┘
     │                        │                        │
     ▼                        ▼                        ▼
┌──────────────┐    ┌──────────────────┐    ┌──────────────────┐
│   INGESTION  │    │    PROCESSOR     │    │     AUDIT        │
│   SERVICE    │    │    SERVICE       │    │     SERVICE      │
│              │    │                  │    │                  │
│ • Consume    │    │ • Validate       │    │ • Consume        │
│   inbound.*  │    │ • Normalise      │    │   audit.events   │
│ • Dedup      │    │ • Persist        │    │ • Batch persist  │
│ • Schema     │    │ • Dispatch       │    │ • Archive        │
│ • Enrich     │    │   (async pool)   │    │ • Metrics        │
│              │    │ • Retry logic    │    │                  │
│              │    │ • DLT routing    │    │                  │
└──────────────┘    └──────────────────┘    └──────────────────┘
```

| Concern | 5 Services | 3 Services |
|---------|-----------|-----------|
| **Operational complexity** | 5 deployments, 5 HPA configs, 5 log streams | 3 of each |
| **Network hops per message** | 4 Kafka hops minimum | 2 Kafka hops |
| **Latency** | Higher (each hop = serialise → produce → consume → deserialise) | Lower |
| **DB connection pools** | 5 × 20 = 100 connections | 3 × 20 = 60 connections |
| **Team size needed** | 5+ developers | 2–3 developers |
| **Independent scaling** | Each service scales independently | Processor service handles the bulk |
| **When to choose** | > 50K msg/s; dedicated platform team; different SLAs per stage | < 50K msg/s; smaller team; uniform SLA |

### Bounded Context Justification (Both Models)

**Why Ingestion is always separate**: It's the only service that deals with raw, untrusted upstream formats. It shields the rest of the system from malformed input. Different upstreams may have different schemas, auth mechanisms, and ingestion rates. It also owns the dedup concern, which is stateful (Redis + DB) and shouldn't be coupled with business logic.

**Why Audit is always separate**: Audit writes must never block or slow the main processing path. If the audit DB is slow, it should not cause consumer lag on the main pipeline. It also has a fundamentally different retention/archival lifecycle (365 days vs 90 days).

**Why Processing + Dispatch + Retry can merge**: These three services operate on the same data model (`EventEnvelope`), share the same DB tables, and have a tight request-response relationship. Merging them eliminates 2 Kafka hops (processing → dispatch and dispatch → retry), reduces latency by ~20–50ms per message, and simplifies the DB transaction boundary (validate + persist + dispatch status in one transaction).

### Recommended: Start with 3, Split When Needed

Split signals: dispatch vendor latency causes consumer lag in processing; retry volume is high enough to warrant independent scaling; team grows to 5+ engineers.

---

## Q5. Workflow Diagram

See `docs/workflow-diagram.mermaid` — a comprehensive Mermaid flowchart showing:
- All 5 upstream sources → Kafka inbound topics
- Ingestion pipeline (dedup, schema, enrich) with error exit paths
- Processing pipeline (factory resolution, validation, normalisation, batch persist)
- Dispatch pipeline (rate limiter, circuit breaker, async vendor call)
- Retry loop (expiry check, max-retry check, exponential backoff, DLT)
- Vendor callback flow
- Audit as cross-cutting async concern
- Every error code at the point where it can occur

---

## Q6. Bulk DB Operations (Avoiding N × DB Hits)

### The Problem

At 5K msg/s per upstream, with 5 upstreams = 25–30K msg/s. If each message results in:
- 1 INSERT into `events`
- 1 INSERT into `event_dispatches`
- 1 INSERT into `audit_log`
- 1 UPDATE to `events.status`

That's 4 × 30K = **120K individual DB round-trips per second**. Oracle can handle this, but connection pool pressure, network latency, and latch contention make it unsustainable.

### The Solution: Batch Everything

```
┌─────────────────────────────────────────────────────┐
│ Kafka poll() returns batch of 500 records            │
│                                                     │
│ 1. Process all 500 in async thread pool             │
│ 2. Collect results into batch buffers               │
│ 3. Execute SINGLE JDBC batch operations:            │
│    • 1 batch INSERT for 500 events                  │
│    • 1 batch INSERT for 500 dispatches              │
│    • 1 batch UPDATE for 500 status changes          │
│    • 1 batch INSERT for 500 audit records           │
│                                                     │
│ 500 records → 4 DB round-trips (not 2,000)          │
│ 30,000 msg/s → 240 DB round-trips/s (not 120,000)  │
└─────────────────────────────────────────────────────┘
```

### Oracle-Specific Optimisations

```sql
-- Use INSERT ALL for multi-row insert in single statement
INSERT ALL
  INTO events (event_id, correlation_id, ...) VALUES (seq_event_id.NEXTVAL, ?, ...)
  INTO events (event_id, correlation_id, ...) VALUES (seq_event_id.NEXTVAL, ?, ...)
  -- ... up to 500 rows
SELECT 1 FROM DUAL;

-- Or use JDBC batch with PreparedStatement.addBatch()
-- Oracle driver with defaultBatchValue=500 is highly efficient
```

### Batch Write Metrics

| Approach | DB Round-Trips/s at 30K msg/s | Latency Impact |
|----------|-------------------------------|----------------|
| Individual INSERT per message | 120,000 | Unsustainable |
| JDBC batch (500 per batch) | 240 | Minimal |
| JDBC batch + async commit | 120 | Negligible |

### Key Config for Oracle JDBC Batching

```yaml
spring:
  datasource:
    hikari:
      data-source-properties:
        defaultBatchValue: 500
        oracle.jdbc.batch.style: STANDARD
    url: jdbc:oracle:thin:@host:1521/XEPDB1?rewriteBatchedStatements=true
```
