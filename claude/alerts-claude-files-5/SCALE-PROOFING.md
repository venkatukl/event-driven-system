# Scale-Proofing Analysis — What Breaks at 15 Upstreams / 75K+ msg/s

## Honest Assessment of Current State

The current design handles 5–6 upstreams at 25–30K msg/s well. But "10 more upstreams in 2 years" means 75–80K msg/s aggregate, 15 inbound topics, potentially 15 different message schemas, and 15 different teams pushing changes to your platform. That's a different beast. Let me be specific about what will and won't survive that growth.

### What Already Scales Fine (No Changes Needed)

These patterns are inherently horizontal and will handle 15 upstreams without modification:

1. **Factory pattern for upstream processors** — Adding upstream #16 is still just a new @Component + config row. The factory auto-discovers beans. This was designed for exactly this scenario.

2. **Channel adapters** — Dispatch channels don't grow with upstreams. You still have SMS/Email/OTP/Voice/Webhook/Push regardless of whether you have 5 or 50 upstreams.

3. **Error code taxonomy** — The stage-based error codes (ING/VAL/PRC/DSP/RTY/SYS) are upstream-agnostic. No changes needed.

4. **JDBC batch operations** — Batch INSERT scales linearly. At 75K msg/s with batch=500, that's 150 batches/second. Oracle can handle this comfortably.

5. **Outbox pattern** — The outbox table grows proportionally but the poller scales with pod count.

6. **Audit pipeline** — Already decoupled via Kafka. More volume just means more partitions on audit.events.

---

## What WILL Break (And Solutions)

### 1. SINGLE processing.events TOPIC BECOMES A BOTTLENECK

**The problem**: Today, all 5 upstreams funnel through one `processing.events` topic with 10 partitions. At 75K msg/s, that's 7,500 msg/s per partition. A single consumer thread can handle this only if processing time is under 0.13ms per message — possible for pure CPU work, but not if any I/O is involved.

More critically, with 10 partitions, you can run at most 10 consumer threads. Even with async dispatch, the consumer poll loop itself becomes the bottleneck.

**The solution — Topic-per-upstream for processing**:

```
BEFORE (current):
  inbound.banking    ─┐
  inbound.insurance  ─┤──→ processing.events (single topic, 10 partitions)
  inbound.otp-auth   ─┤
  inbound.marketing  ─┘
  
AFTER (scaled):
  inbound.banking    ──→ processing.banking    (10 partitions)
  inbound.insurance  ──→ processing.insurance  (10 partitions)
  inbound.otp-auth   ──→ processing.otp-auth   (10 partitions)
  inbound.marketing  ──→ processing.marketing  (10 partitions)
  ...15 upstreams    ──→ 15 processing topics
```

This gives you 150 partitions of processing capacity (15 topics × 10 partitions) instead of 10. Each upstream gets its own consumer group and can scale independently. A misbehaving upstream's lag doesn't affect others.

The ingestion service already uses `topicPattern = "inbound\\..*"`. Change processing to use `topicPattern = "processing\\..*"`. The routing is trivial — just mirror the upstream name into the processing topic name.

**When to migrate**: When `processing.events` consumer lag consistently exceeds 10,000 records, or when you hit upstream #8–10.

### 2. ORACLE BECOMES THE CEILING

**The problem**: At 75K msg/s, even with batch INSERT, the events table receives 150 batch INSERTs/s. The event_dispatches table receives another 150. Audit_log gets 150. That's 450 batch operations/second, each containing 500 rows. Oracle can handle this on good hardware, but:

- Sequence contention: Even with CACHE 1000, the `seq_event_id` sequence is shared across all pods. At 75K/s, you burn through the cache in 13 seconds, causing periodic latch waits.
- Index maintenance: 6 indexes on the events table means each INSERT updates 6 B-tree structures.
- Partitioned table overhead: Monthly interval partitions work, but the partition pruning on queries must be correct or full scans kill performance.
- Redo log generation: At 75K rows/s with CLOB payload, you generate massive redo. Make sure redo log groups are sized appropriately (1GB+ each).

**Solutions**:

**A. Sequence sharding (immediate)**:
```sql
-- Instead of one sequence, use per-pod sequences with offset
-- Pod 0: seq_event_id_0 START WITH 0 INCREMENT BY 10
-- Pod 1: seq_event_id_1 START WITH 1 INCREMENT BY 10
-- Pod 2: seq_event_id_2 START WITH 2 INCREMENT BY 10
-- This eliminates all latch contention — each pod has its own sequence
```

Or use application-generated UUIDs / TSID (time-sorted IDs) instead of sequences entirely. TSIDs are sortable, globally unique, and have zero DB overhead.

**B. Write-aside to events table (medium-term)**:
Not every field needs to be persisted synchronously. Consider:
```
Hot path (synchronous, in batch INSERT):
  event_id, correlation_id, upstream_id, channel, status, created_at

Cold path (async, via audit service):
  payload (CLOB), subject, template_id, error_detail
```
This halves the per-row write cost because CLOBs are expensive.

**C. Read replica for dashboards/queries**:
Never run ad-hoc queries against the write instance. Use Oracle Active Data Guard or a read replica. This is operational, not architectural, but it will bite you when an analyst runs `SELECT COUNT(*) FROM events WHERE status = 'FAILED'` and locks the entire table.

### 3. REDIS DEDUP CACHE MEMORY EXPLOSION

**The problem**: The dedup cache stores a Redis key per message for 24 hours. At 75K msg/s × 86,400 seconds × ~100 bytes per key = **~580GB of Redis data per day**. This is unsustainable.

**The solution — Bloom filter with probabilistic dedup**:
```java
// Replace Redis key-per-message with a Bloom filter
// 75K msg/s × 86,400s = ~6.5 billion entries/day
// Bloom filter at 1% false-positive rate = ~7.6 GB (not 580 GB)
// Use Redisson's RBloomFilter or Guava's BloomFilter

RBloomFilter<String> dedupFilter = redissonClient.getBloomFilter("dedup");
dedupFilter.tryInit(7_000_000_000L, 0.01); // 7B capacity, 1% FPR

// Check
if (dedupFilter.contains(dedupKey)) {
    // Likely duplicate (1% false positive acceptable — DB is authoritative)
    return;
}
// Not in bloom → check DB → if not there either, mark in bloom + DB
dedupFilter.add(dedupKey);
```

Bloom filters are the industry standard for high-volume dedup. The DB remains the authoritative source; the bloom filter just prevents 99% of DB lookups.

**When to migrate**: When Redis memory usage for dedup keys exceeds 10GB.

### 4. PER-UPSTREAM RESOURCE ISOLATION IS MISSING

**The problem today**: All upstreams share the same processing pods, the same DB connection pool, the same async thread pool. If upstream #12 sends malformed messages that cause slow validation (e.g., regex backtracking), it steals threads from upstream #1's OTP messages.

**The solution — Bulkhead per upstream**:
```java
@Component
public class UpstreamBulkheadRegistry {
    
    private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    
    public Bulkhead getBulkhead(String upstreamId) {
        return bulkheads.computeIfAbsent(upstreamId, id -> {
            BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(10)      // max 10 concurrent per upstream
                .maxWaitDuration(Duration.ofMillis(500))
                .build();
            return Bulkhead.of("upstream-" + id, config);
        });
    }
}

// In processing:
Bulkhead bulkhead = bulkheadRegistry.getBulkhead(envelope.getUpstreamId());
Bulkhead.decorateRunnable(bulkhead, () -> processEvent(envelope)).run();
```

This ensures no single upstream can consume more than its share of processing threads. Critical for multi-tenant fairness.

### 5. UPSTREAM ONBOARDING IS STILL DEVELOPER-DEPENDENT

**The problem**: Adding upstream #16 requires a Java developer to write an `UpstreamProcessor` implementation, compile it, test it, and deploy it. At 15 upstreams, this means platform team is a bottleneck for onboarding.

**The solution — Scriptable processors for simple upstreams**:

Most upstreams follow a pattern: extract fields A, B, C from JSON → validate format → map to channel → set priority. This can be config-driven:

```json
// upstream_config.config_json for a simple upstream:
{
    "schemaVersion": 1,
    "requiredFields": ["recipient", "message", "transactionId"],
    "recipientField": "recipient",
    "recipientFormat": "PHONE",
    "channelMapping": {
        "default": "SMS",
        "rules": [
            {"field": "priority", "equals": "HIGH", "channel": "OTP"}
        ]
    },
    "priority": 3,
    "templateId": "GENERIC_ALERT_V1"
}
```

```java
@Component("configurableProcessor")
public class ConfigurableUpstreamProcessor implements UpstreamProcessor {
    
    // This single processor handles ANY upstream whose config_json 
    // follows the schema above. No new code needed.
    
    @Override
    public String getUpstreamId() { return "CONFIGURABLE"; }
    
    @Override
    public void validate(EventEnvelope envelope) {
        UpstreamConfig config = configCache.get(envelope.getUpstreamId());
        JsonNode rules = objectMapper.readTree(config.getConfigJson());
        
        for (String field : rules.get("requiredFields").asArray()) {
            if (!payloadContains(envelope.getPayload(), field)) {
                throw new NonRetryableException(ErrorCode.VAL_SCHEMA_001, ...);
            }
        }
        // Validate recipient format
        String format = rules.get("recipientFormat").asText();
        validateRecipientFormat(envelope.getRecipient(), format);
    }
    
    @Override
    public EventEnvelope normalise(EventEnvelope envelope) {
        // Apply channel mapping rules from config
        // Set priority from config
        // Resolve template from config
        return envelope;
    }
}
```

Now adding a new simple upstream is: INSERT a row in `upstream_config` with the right `config_json`, create the Kafka topic, done. Zero code deployment. Reserve custom `UpstreamProcessor` implementations only for upstreams with genuinely complex business logic.

### 6. NO UPSTREAM SLA TRACKING OR QUOTA MANAGEMENT

**The problem**: With 15 upstreams, you need to know: who's sending how much, who's exceeding their agreed rate, who's generating the most failures, and who's responsible when the platform is slow.

**The solution — Upstream metering table + dashboard**:

```sql
CREATE TABLE upstream_metrics_hourly (
    upstream_id     VARCHAR2(32)   NOT NULL,
    hour_bucket     TIMESTAMP      NOT NULL,  -- truncated to hour
    msgs_received   NUMBER(19)     DEFAULT 0,
    msgs_validated  NUMBER(19)     DEFAULT 0,
    msgs_dispatched NUMBER(19)     DEFAULT 0,
    msgs_failed     NUMBER(19)     DEFAULT 0,
    msgs_dlt        NUMBER(19)     DEFAULT 0,
    avg_latency_ms  NUMBER(10)     DEFAULT 0,
    p99_latency_ms  NUMBER(10)     DEFAULT 0,
    CONSTRAINT pk_upstream_metrics PRIMARY KEY (upstream_id, hour_bucket)
);
```

Populated by the audit service from audit.events. This gives you per-upstream SLA reporting: "Banking is at 99.97% delivery rate, Marketing is at 98.2% — here's why."

Also add quota enforcement:
```java
// In ingestion, before processing:
long hourlyCount = metricsCache.getHourlyCount(upstreamId);
long hourlyQuota = upstreamConfig.getHourlyQuota(); // from upstream_config
if (hourlyCount >= hourlyQuota) {
    log.warn("Upstream {} exceeded hourly quota: {}/{}", upstreamId, hourlyCount, hourlyQuota);
    // Option A: reject (return error to upstream)
    // Option B: deprioritise (set priority = 9, process in background)
    // Option C: buffer (pause consumer for this upstream)
}
```

### 7. NO MULTI-REGION / DR STORY

**The problem**: Everything runs in one OCP cluster. If the cluster goes down, all 15 upstreams go dark.

**The solution (plan now, implement when needed)**:

**Active-Passive (simplest)**:
- Primary cluster processes everything.
- Standby cluster has identical deployments but consumers are paused.
- Kafka MirrorMaker 2 replicates topics to standby cluster.
- On primary failure: unpause consumers on standby. RPO = replication lag (~seconds).

**Active-Active (complex, for later)**:
- Each upstream is assigned a "home" cluster.
- Kafka cross-cluster replication for shared topics (retry, dlt, audit).
- Oracle Data Guard for DB replication.
- Route53 / OCP global load balancer for vendor callbacks.

**Recommendation**: Document the Active-Passive design now, implement it when you reach upstream #10 or when the business demands it.

### 8. DEPLOYMENT COUPLING — ALL UPSTREAMS SHARE ONE BINARY

**The problem**: When you deploy a fix for Banking's upstream processor, you redeploy the entire processing-service, which restarts consumers for all 15 upstreams. This causes brief consumer lag spikes across the board.

**The solution at scale — Processor plugins or upstream-scoped deployment units**:

**Short term (fine for 10–15 upstreams)**: Rolling update with 3+ pods. At any time, 2 of 3 pods are running. Consumer lag spike is minimal because static group membership avoids full rebalance.

**Long term (15+ upstreams)**: Split the processing service by upstream "tier":
```
processing-service-critical  (banking, otp-auth, insurance)
processing-service-standard  (logistics, payments, ...)
processing-service-bulk      (marketing, campaigns, ...)
```
Each tier is independently deployable. Banking doesn't feel a deployment to the marketing tier.

### 9. TESTING AT SCALE — NO LOAD TEST INFRASTRUCTURE

**The problem**: You can't know if the system handles 75K msg/s without testing it. Integration tests with 100 messages prove correctness, not capacity.

**The solution — Performance test harness**:

```java
// Kafka producer load generator (run as separate pod)
@Component
public class LoadGenerator {
    
    @Scheduled(fixedRate = 1) // every 1ms
    public void generateBatch() {
        for (int i = 0; i < 75; i++) { // 75 messages per ms = 75K/s
            String upstream = upstreams[random.nextInt(upstreams.length)];
            String payload = templatePayloads.get(upstream);
            kafkaTemplate.send("inbound." + upstream, UUID.randomUUID().toString(), payload);
        }
    }
}
```

Run this against a staging environment with production-sized Oracle and Kafka. Measure:
- End-to-end latency (P50, P95, P99)
- Consumer lag per topic
- DB connection pool utilisation
- Oracle AWR report (wait events, redo generation, latch contention)
- Redis memory usage
- GC pause times

**Run this before going to production with upstream #7–8, not after.**

### 10. UPSTREAM CONTRACT TESTING IS MISSING

**The problem**: Upstream #12 changes their payload format on a Friday evening. Your ingestion service starts rejecting every message. You don't know until Monday.

**The solution — Consumer-driven contract tests**:

Each upstream provides a contract (JSON Schema or Pact file) that defines their message format. Your CI pipeline validates:
1. Their sample messages against your schema validation.
2. Your processors against their sample messages.
3. Any schema change triggers a compatibility check before deployment.

```json
// contracts/banking/message-schema.json
{
    "$schema": "http://json-schema.org/draft-07/schema#",
    "type": "object",
    "required": ["recipient", "payload", "upstreamMsgId"],
    "properties": {
        "recipient": { "type": "string", "pattern": "^\\+?[1-9]\\d{7,14}$" },
        "payload": {
            "type": "object",
            "required": ["transactionId", "amount", "message"],
            "properties": {
                "transactionId": { "type": "string" },
                "amount": { "type": "number" },
                "message": { "type": "string", "maxLength": 160 }
            }
        }
    }
}
```

Store these in a `contracts/` directory. Run schema validation in CI. If an upstream wants to change their format, they update the contract, your CI validates compatibility, and you can adapt before deployment.

---

## Revised Priority Matrix (Scaling to 15 Upstreams)

| Priority | Item | When to Implement | Breaks At |
|----------|------|-------------------|-----------|
| **P0 — Do Now** | Per-upstream bulkhead (resource isolation) | Before upstream #7 | Multi-tenant fairness fails |
| **P0 — Do Now** | Upstream metering + quota enforcement | Before upstream #7 | No SLA visibility, no abuse prevention |
| **P0 — Do Now** | Configurable processor (no-code onboarding) | Before upstream #7 | Platform team becomes bottleneck |
| **P0 — Do Now** | Load test harness | Before upstream #7 | Blind to capacity limits |
| **P1 — Plan Now, Build Soon** | Topic-per-upstream for processing | Before upstream #10 | processing.events becomes bottleneck |
| **P1 — Plan Now, Build Soon** | Bloom filter for dedup (replace Redis keys) | When Redis > 10GB | Redis OOM |
| **P1 — Plan Now, Build Soon** | Sequence sharding or TSID | When sequence latch waits appear in AWR | Insert throughput degrades |
| **P1 — Plan Now, Build Soon** | Upstream contract testing | Before upstream #8 | Schema changes cause outages |
| **P2 — Design Now, Build Later** | Tiered deployment (critical/standard/bulk) | At upstream #15+ | Deployment coupling |
| **P2 — Design Now, Build Later** | Active-Passive DR | At upstream #10 or business mandate | Single-cluster failure |
| **P2 — Design Now, Build Later** | Write-aside for CLOB payloads | When Oracle redo log > 50GB/hour | DB write throughput ceiling |
| **P2 — Design Now, Build Later** | Read replica for queries/dashboards | When analysts start querying production | Ad-hoc queries block writes |

---

## What We Are NOT Missing (Patterns That Are Overhyped for This Use Case)

Let me also call out patterns that blog posts and architecture talks will tell you to add but that would be over-engineering for this system:

**CQRS (Command Query Responsibility Segregation)**: Our write path (events table) and read path (audit dashboard) are already separate. The audit service IS the read model. Adding a formal CQRS framework would add complexity without benefit.

**Event Sourcing**: We don't need to rebuild state from events. The events table IS the state. Event sourcing would require a full event store, projection engine, and snapshot management — massive overhead for a notification platform.

**Saga Pattern / Distributed Transactions**: We don't have cross-service business transactions. Each message flows one way through the pipeline. The outbox pattern handles the only consistency boundary (DB → Kafka). Sagas would be solving a problem we don't have.

**Service Mesh (Istio/Linkerd)**: Our services communicate via Kafka, not HTTP. A service mesh adds mTLS and observability for HTTP service-to-service calls, which we barely have (only vendor callbacks). The overhead isn't justified.

**GraphQL / API Gateway**: Our upstreams publish to Kafka, not to an API. There's no user-facing API to gateway. If you add a REST API for upstream onboarding or dashboard queries later, an API gateway makes sense then — not now.

**Kubernetes Operators**: A custom operator for managing upstream lifecycle (topic creation, config injection, processor deployment) would be elegant at 50+ upstreams. At 15, it's premature. Stick with scripts and ConfigMaps.
