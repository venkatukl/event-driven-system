# Copilot development guide — CBD Alerts Platform

> Phase-by-phase implementation workflow using GitHub Copilot Agent mode.

---

## Phase overview

| Phase | Duration | Focus |
|-------|----------|-------|
| Phase 0 | 1–2 weeks | Legacy extraction (Copilot Agent + Claude Sonnet) |
| Phase 1 | 1–2 weeks | Architecture & design (this doc + ARCHITECTURE.md) |
| Phase 2 | 1 week | Foundation & spike |
| Phase 3 | 3–4 weeks | Core implementation |
| Phase 4 | 1–2 weeks | Integration & performance testing |
| Phase 5 | 1–2 weeks per upstream | Deployment & cutover |
| Phase 6 | 2–4 weeks | Hypercare & decommission |

---

## Phase 2 — Foundation & spike

### 2.1 Project scaffolding
Use Copilot to generate:
- Multi-module Gradle project (`build.gradle.kts`, `settings.gradle.kts`)
- Spring Boot 3.2.x parent for each service module
- `platform-common` shared library module
- Docker/OCP base configurations

**Copilot prompt**:
```
Create a multi-module Gradle Kotlin DSL project with modules: common, ingestion-service, 
processing-service, dispatch-service, retry-service, audit-service. Use Spring Boot 3.2.x, 
Java 17, Spring Kafka, Spring Data MongoDB, Redis, Resilience4J, Micrometer. 
See @workspace .github/copilot-instructions.md for patterns.
```

### 2.2 Common library (platform-common)
Implement in order:
1. `ErrorCode` enum — 25+ codes with ING/VAL/PRC/DSP/RTY/SYS prefix
2. `EventEnvelope` record — core message model
3. `EventStatus` enum — RECEIVED, VALIDATED, PROCESSED, DISPATCHED, FAILED, RETRIED, DLT
4. `Priority` enum — HIGH(1), MEDIUM(2), LOW(3)
5. `ChannelType` enum — SMS, VOICE, EMAIL, PUSH, WEBHOOK
6. `PlatformException` hierarchy — typed exceptions per error code
7. `ProcessingStep` interface — pipeline step contract
8. `StepConfig` — step configuration holder
9. `EventContext` — mutable context passed through pipeline
10. `PlatformMetrics` — Micrometer metrics registry
11. Kafka configuration classes — consumer factories per priority tier
12. `AuditEventPublisher` — publishes to audit.events topic

### 2.3 MongoDB spike
Validate:
- Connection to MongoDB replica set
- Bulk write performance (target: 500 docs/batch in <50ms)
- TTL index auto-expiry
- Unique index on `dedupLog.dedupKey`
- `findOneAndUpdate` lease pattern for outbox poller
- `$inc` upsert for metrics aggregation

### 2.4 Kafka spike
Validate:
- Batch consumer with manual ack
- 3 consumer containers per pod (high/med/low) with separate consumer groups
- Static group membership (`group.instance.id`)
- Producer to priority-tiered topics
- Consumer lag monitoring via Micrometer

---

## Phase 3 — Core implementation

### 3.1 Ingestion service

**Step 1: Inbound adapters**
```
Implement InboundAdapter interface with KafkaInboundAdapter, MqInboundAdapter, 
FileInboundAdapter. Each normalizes to EventEnvelope. Use adapter pattern.
See #file:common/src/main/java/com/platform/common/model/EventEnvelope.java
```

**Step 2: Schema validation**
```
Implement SchemaValidationService that validates EventEnvelope against 
upstream-specific JSON schemas stored in upstreamConfig MongoDB collection.
Reject with ErrorCode.ING_SCHEMA_INVALID on failure.
```

**Step 3: Three-tier deduplication**
```
Implement DeduplicationService with 3 tiers:
1. Redis GET dedup:{upstream}:{messageId} — fast path
2. MongoDB dedupLog findOne by dedupKey — fallback on Redis miss
3. Vendor idempotency key — documented in ChannelAdapter contract
On new message: SET Redis with 90-day TTL + INSERT dedupLog.
Use unique index on dedupKey — catch DuplicateKeyException for concurrent writes.
```

**Step 4: Priority classification and publish**
```
After dedup + validate, read upstreamConfig to determine priority.
Publish to processor.{priority} topic based on upstream default priority 
with event-type overrides. Include all metadata in Kafka headers.
```

### 3.2 Processing service

**Step 1: Configurable pipeline executor**
```
Implement ConfigurablePipelineExecutor that:
1. Loads pipeline steps from pipelineConfig collection (cached in Redis, 5 min TTL)
2. Resolves each step by Spring bean name from step registry
3. Executes steps in phase+stepOrder order (PRE → PROCESS → POST)
4. Evaluates SpEL condition expressions for conditional execution
5. Handles onFailure: REJECT/SKIP/CONTINUE per step
See #file:docs/ARCHITECTURE.md section 6 for step library.
```

**Step 2: Pre-processor enrichment steps**
```
Implement reusable ProcessingStep beans:
- CustomerProfileEnricher: calls Entitlements via API Gateway, wrapped in R4J CircuitBreaker
- TemplateResolver: calls CMS via API Gateway
- AccountPreferenceEnricher: reads from Redis cache (user & account preferences)
- StaticDefaultsEnricher: injects defaults from stepConfig JSON
Each step reads its config from StepConfig (loaded from pipelineConfig collection).
```

**Step 3: Upstream processor (factory)**
```
Implement UpstreamProcessorFactory with:
- ConfigurableUpstreamProcessor: handles simple upstreams via DB config (field mapping, 
  format conversion, default injection) — zero code deployment
- Named implementations (e.g., BankingUpstreamProcessor) for complex business logic
Factory resolves processor class from upstreamConfig.processorClass field.
```

**Step 4: Post-processor validation steps**
```
Implement reusable steps: RequiredFieldsValidator, FieldFormatValidator, 
PriorityOverrideStep, ChannelRouter, DispatchTopicResolver.
All config-driven via pipelineConfig collection.
```

**Step 5: MongoDB write + embedded outbox**
```
After pipeline completes, write event document to MongoDB events collection.
Document includes payload, normalizedPayload, outboxStatus="PENDING", 
outboxTarget="dispatch.{priority}". Single document write — no multi-doc transaction.
Use bulkWrite for batch processing (500 docs/batch).
```

**Step 6: Outbox poller**
```
Implement OutboxPoller as @Scheduled task inside processing-service.
Uses findOneAndUpdate with lease pattern:
- Query: outboxStatus=PENDING AND (leasedBy=null OR leaseExpiry < now)
- Update: set leasedBy=podId, leaseExpiry=now+30s
- Sort: priority ASC, createdAt ASC (high priority first)
- Batch: claim 500 docs, publish to Kafka, update outboxStatus=PUBLISHED
Multi-pod safe — lease prevents duplicate processing.
```

### 3.3 Dispatch service

**Step 1: Priority-tiered consumers**
```
Create 3 ConcurrentKafkaListenerContainerFactory beans (highPriorityFactory, 
mediumPriorityFactory, lowPriorityFactory) with different concurrency and poll intervals.
Each @KafkaListener uses its own factory and consumer group.
See #file:docs/ARCHITECTURE.md section 4 for thread allocation.
```

**Step 2: Async thread pool + channel adapter**
```
Consumer hands off to async pool immediately via CompletableFuture.
ChannelAdapterFactory resolves adapter by envelope.channel.
Each adapter calls vendor API via API Gateway, passing event_id as idempotency key.
Write dispatch result to eventDispatches collection.
Update event document status to DISPATCHED or FAILED.
```

**Step 3: Resilience4J configuration**
```
Configure per-vendor CircuitBreaker, per-vendor RateLimiter, per-upstream Bulkhead.
Use Resilience4J Spring Boot starter with YAML configuration.
Circuit breaker config in channelVendorConfig collection.
See #file:docs/ARCHITECTURE.md section 7.
```

### 3.4 Retry service
```
Implement RetryService consuming retry.events topic.
On receive: upsert eventRetries doc (attemptCount, nextRetryAt with backoff + jitter).
If attemptCount < maxAttempts: re-publish to dispatch.{priority} after backoff.
If exhausted: publish to dlt.events with ErrorCode.RTY_MAX_EXCEEDED.
```

### 3.5 Audit service
```
Implement AuditService consuming audit.events and dlt.events topics.
Bulk insert to auditLog collection (1000/batch).
Aggregate metrics to upstreamMetrics collection using $inc upsert.
Coordinate with ZL Technologies archival for long-term storage.
```

---

## Phase 4 — Testing

### Integration test setup
```
Use Testcontainers for:
- MongoDB (replica set for transaction/lease testing)
- Kafka (KRaft mode)
- Redis

Test scenarios:
1. Happy path: message → ingestion → processing → dispatch → vendor mock
2. Dedup: same message twice → second rejected
3. Priority: high message processed before low under load
4. Retry: vendor failure → retry.events → re-dispatch → success
5. DLT: max retries exceeded → dlt.events
6. Outbox: MongoDB write succeeds, Kafka down → outbox drains on recovery
7. Circuit breaker: vendor failures → circuit opens → calls rejected → circuit half-open → recovery
```

---

## Copilot tips

1. Always reference `@workspace .github/copilot-instructions.md` in prompts
2. Use `#file:` to point Copilot at specific interfaces when implementing
3. Ask Copilot to generate tests alongside implementation
4. For pipeline steps: implement one step fully, then ask Copilot to generate similar steps following the same pattern
5. For MongoDB operations: always specify write concern and read preference in prompts
6. Use Copilot Plan mode for multi-file changes (e.g., adding a new processing step)
