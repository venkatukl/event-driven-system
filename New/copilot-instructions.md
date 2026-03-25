# Copilot instructions — CBD Alerts Platform

> This file auto-loads in every GitHub Copilot interaction. It provides context about the system architecture, coding standards, and patterns to follow.

---

## System context

You are working on an event-driven notification platform (CBD Alerts) that ingests messages from 10+ upstream business systems via MQ, Kafka, and file-based channels, processes them through configurable pipelines, and dispatches notifications via SMS, Voice, and other vendor APIs.

**Tech stack**: Java 17, Spring Boot 3.2.x, Spring Kafka, MongoDB, Redis Cluster, Resilience4J, Micrometer, Gradle, OpenShift.

**Scale**: 5M messages/day, ~1,200 msg/s peak, 19 Kafka topics, 190 partitions, active-active multi-DC.

---

## Architecture — 5 microservices

```
Upstream (MQ/Kafka/File) → Kafka inbound.{upstream}
  → INGESTION (adapter, schema validate, 3-tier dedup, normalize, assign priority)
  → Kafka processor.{high|medium|low}
  → PROCESSING (pre-processor enrichment → upstream processor transformation → post-processor validation → MongoDB write + outbox)
  → Kafka dispatch.{high|medium|low}
  → DISPATCH (async pool 50 threads → channel adapter → Resilience4J → vendor API via API Gateway)
  → Kafka retry.events → RETRY (backoff 30s→15m, re-dispatch or DLT)
  → Kafka audit.events → AUDIT (bulk insert, metrics aggregation, ZL archival)
```

---

## Project structure

```
event-platform/
├── .github/copilot-instructions.md     ← This file
├── docs/
│   ├── ARCHITECTURE.md                  ← Core system design
│   └── COPILOT-DEV-GUIDE.md            ← Phase-by-phase implementation guide
├── common/src/main/java/com/platform/common/
│   ├── error/       ← ErrorCode enum, PlatformException hierarchy
│   ├── model/       ← EventEnvelope, ChannelType, EventStatus, Priority
│   ├── factory/     ← UpstreamProcessor, ChannelAdapter, factory interfaces
│   ├── config/      ← KafkaConsumerConfig, AsyncProcessingConfig
│   ├── kafka/       ← AuditEventPublisher
│   ├── metrics/     ← PlatformMetrics (Micrometer instrumentation)
│   └── pipeline/    ← ProcessingStep interface, StepConfig, PipelineExecutor
├── services/
│   ├── ingestion-service/
│   ├── processing-service/
│   ├── dispatch-service/
│   ├── retry-service/
│   └── audit-service/
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Coding standards

### General
- Java 17 features: records, sealed interfaces, pattern matching, text blocks
- Spring Boot 3.2.x conventions
- NO JPA/Hibernate — use MongoDB Spring Data repositories or MongoTemplate directly
- NO Lombok — use Java records for DTOs, manual getters where needed
- Gradle Kotlin DSL for build files
- All classes under `com.platform.{module}` package

### MongoDB
- Use `MongoTemplate` for complex queries and bulk operations
- Use Spring Data `MongoRepository` only for simple CRUD on config collections
- `bulkWrite()` with unordered operations for batch inserts
- TTL indexes on `createdAt` for all operational collections (90 days)
- Write concern: `w: "majority"` for event writes, `w: 1` for audit/metrics
- Embedded outbox pattern: `outboxStatus` field on event document, lease-based poller

### Kafka
- Spring Kafka `@KafkaListener` with `ConcurrentKafkaListenerContainerFactory`
- Separate consumer group per priority tier: `{service}-high`, `{service}-medium`, `{service}-low`
- Manual acknowledgment (`AckMode.MANUAL`) everywhere
- Batch listeners (`setBatchListener(true)`)
- `CooperativeStickyAssignor` + static group membership (`group.instance.id = HOSTNAME`)
- All topic names in constants: `KafkaTopics.PROCESSOR_HIGH`, etc.

### Resilience
- Resilience4J `@CircuitBreaker` on all external calls (SORs, vendor APIs, MongoDB where applicable)
- Resilience4J `@RateLimiter` per vendor
- Resilience4J `@Bulkhead` per upstream in dispatch
- Spring Kafka `DefaultErrorHandler` with `ExponentialBackOff` at ingestion/processing layers
- All error codes from `ErrorCode` enum with prefix (ING/VAL/PRC/DSP/RTY/SYS) and `retryable` flag

### Observability
- Micrometer `@Timed` and custom counters/gauges via `PlatformMetrics`
- Structured JSON logging via logback-spring.xml
- MDC fields: correlationId, upstreamId, eventId, channel, priority
- 17 custom business metrics + auto-exposed JVM/Kafka/connection pool metrics

### Testing
- Unit tests: JUnit 5 + Mockito
- Integration tests: Testcontainers (MongoDB, Kafka, Redis)
- Contract tests: upstream schema validation
- Test naming: `should_[expected]_when_[condition]`

---

## Key patterns to follow

### 1. EventEnvelope — the core model
Every message through the system is wrapped in an `EventEnvelope`:
```java
public record EventEnvelope(
    String eventId,
    String upstreamId,
    String eventType,
    String channel,
    Priority priority,
    EventStatus status,
    String correlationId,
    Map<String, Object> payload,
    Map<String, Object> normalizedPayload,
    Instant createdAt,
    Map<String, String> headers
) {}
```

### 2. Configurable processing pipeline
Processing steps implement `ProcessingStep` interface:
```java
public interface ProcessingStep {
    String name();
    StepResult execute(EventContext context, StepConfig config);
    default boolean supports(EventContext context, StepConfig config) { return true; }
}
```
Steps are registered as Spring beans, referenced by name in `pipelineConfig` MongoDB collection. The `ConfigurablePipelineExecutor` loads the pipeline from DB (cached in Redis) and executes steps in order.

### 3. Adapter pattern for upstream channels
```java
public interface InboundAdapter {
    String channelType(); // "MQ", "KAFKA", "FILE"
    void start();
    void stop();
}
```
Each adapter normalizes to `EventEnvelope` before entering the ingestion pipeline.

### 4. Factory pattern for upstream processors and channel adapters
```java
public interface UpstreamProcessorFactory {
    UpstreamProcessor getProcessor(String upstreamId);
}
public interface ChannelAdapterFactory {
    ChannelAdapter getAdapter(String channel);
}
```

### 5. Error codes
```java
public enum ErrorCode {
    ING_SCHEMA_INVALID("ING-001", "Schema validation failed", false),
    ING_DEDUP_DUPLICATE("ING-002", "Duplicate message", false),
    PRC_STEP_FAILED("PRC-001", "Processing step failed", true),
    PRC_UPSTREAM_ERROR("PRC-002", "Upstream processor error", false),
    DSP_VENDOR_TIMEOUT("DSP-001", "Vendor API timeout", true),
    DSP_VENDOR_5XX("DSP-002", "Vendor server error", true),
    DSP_RATE_LIMITED("DSP-003", "Vendor rate limited", true),
    RTY_MAX_EXCEEDED("RTY-001", "Max retry attempts exceeded", false),
    // ... 25+ codes
    ;
    
    private final String code;
    private final String message;
    private final boolean retryable;
}
```

---

## MongoDB collections reference

| Collection | Primary key | Key fields |
|------------|------------|------------|
| `events` | `_id` (event_id) | upstreamId, status, priority, channel, payload, outboxStatus, outboxTarget |
| `eventDispatches` | ObjectId | eventId, attemptNumber, vendorId, statusCode, latencyMs |
| `eventRetries` | `_id` (event_id) | attemptCount, nextRetryAt, backoffState, maxAttempts |
| `dedupLog` | ObjectId | dedupKey (unique), eventId |
| `auditLog` | ObjectId | eventId, status, previousStatus, serviceName, timestamp |
| `upstreamMetrics` | ObjectId | upstreamId, channel, metricHour, totalCount, successCount |
| `upstreamConfig` | `_id` (upstreamId) | defaultPriority, channelMappings, rateLimits, processorClass |
| `channelVendorConfig` | composite | channel, vendorId, apiEndpoint, credentialRef |
| `pipelineConfig` | ObjectId | upstreamId, phase, stepOrder, stepName, stepConfig |

---

## What NOT to do

- Do NOT use JPA/Hibernate — we use MongoDB with Spring Data / MongoTemplate
- Do NOT use Lombok — use Java records and explicit code
- Do NOT create separate deployments per priority tier — single deployment, thread allocation inside pod
- Do NOT store credentials in MongoDB — use vault references
- Do NOT use auto-commit for Kafka consumers — always manual ack
- Do NOT put business logic in pre/post processors — those are reusable steps. Business logic goes in the upstream processor
- Do NOT skip the outbox pattern — direct Kafka publish from processing loses messages during broker outages
- Do NOT use `@Transactional` — MongoDB transactions are expensive. Use embedded outbox pattern instead
