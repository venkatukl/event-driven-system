# Copilot Project Instructions

## Project Context
This is a Kafka-based event processing platform built with:
- Java 17, Gradle (Kotlin DSL), Spring Boot 3.2.x
- Spring Kafka (batch consumers, manual ack, CooperativeStickyAssignor)
- Oracle DB (JDBC batch operations, NOT JPA/Hibernate)
- Redis (Lettuce, async, dedup cache)
- Resilience4J (circuit breaker, rate limiter, bulkhead)

## Architecture (3 services)
- **ingestion-service**: Kafka consumer for inbound.* topics → dedup (Redis+DB) → schema validation → publish to processing.events
- **processing-service**: Merged processing + dispatch + retry. Consumes processing.events, validates per-upstream, batch-persists to Oracle, dispatches to vendors via async thread pool, handles retries with exponential backoff, routes exhausted messages to DLT.
- **audit-service**: Consumes audit.events → batch-persists to audit_log → metrics → archival
- **platform-common**: Shared library with error codes, models, factories, Kafka/async config

## Code Conventions

### Database
- Use JdbcTemplate with batch operations for ALL DB writes. Never individual INSERT in hot path.
- All tables use NUMBER(19) for IDs with Oracle SEQUENCE (CACHE 1000).
- Pre-fetch sequence values in batch: `SELECT seq.NEXTVAL FROM DUAL CONNECT BY LEVEL <= ?`
- Oracle-specific: use `SYSTIMESTAMP` not `CURRENT_TIMESTAMP`.

### Kafka
- Batch listener mode with manual acknowledgment (MANUAL_IMMEDIATE).
- Consumer concurrency = 5 (10 partitions, 2 pods).
- Producer: idempotent, transactional, lz4 compression, batch.size=65536.
- CooperativeStickyAssignor + static group membership (group.instance.id = HOSTNAME).
- Use outbox pattern for DB→Kafka consistency: INSERT to event_outbox in same TX as events, poll separately.

### Error Handling
- All exceptions must carry an ErrorCode from `com.platform.common.error.ErrorCode` enum.
- Retryable errors → publish to retry.events topic.
- Non-retryable errors → set status=FAILED, log, audit, do not retry.
- Poison pills → handled by DefaultErrorHandler → dlt.events.

### Async Processing
- Use injected `dispatchExecutor` bean (ThreadPoolTaskExecutor, 50 threads) for async vendor dispatch.
- Do NOT use @Async annotation.
- Pattern: submit batch to pool as CompletableFuture → allOf().join() → batch DB write → ack.

### Pluggable Architecture
- New upstreams: implement `UpstreamProcessor` as @Component, insert upstream_config row, create Kafka topic.
- New channels: implement `ChannelAdapter` as @Component, add ChannelType enum value, insert channel_vendor_config row.

### Logging
- JSON logging via Logback + logstash-encoder.
- MDC fields: correlationId, eventId, upstreamId, channel, topic, partition, offset.
- Mask PII (phone, email) in log output.

## Do NOT
- Do not use JPA, Hibernate, or Spring Data JPA. JdbcTemplate only.
- Do not use @Async annotation. Use the named executor beans.
- Do not use Kafka auto-commit.
- Do not create individual DB INSERTs inside loops. Always batch.
- Do not hardcode vendor credentials. Use environment variables / OCP secrets.
- Do not use WidthType.PERCENTAGE in any docx generation.
- Do not generate virtual thread code (requires Java 21; we are on Java 17).
