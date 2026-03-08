# Development Guide — Using GitHub Copilot (Claude Sonnet 4.6) for Implementation

## Why This Guide Exists

GitHub Copilot Agent mode is excellent at implementing well-scoped tasks but degrades on ambiguous, large-scope requests. The key insight: **Copilot's context window is limited, and it works best when you give it focused inputs per task rather than dumping everything at once.**

This guide gives you the exact sequence, the exact files to reference, and the exact prompts to use at each stage.

---

## Phase 0: Repository Setup (You Do This Manually)

Before touching Copilot, set up the Git repository and Gradle project skeleton by hand. This takes 15 minutes and saves hours of Copilot hallucinating build configs.

```bash
# 1. Create repo from the generated project
mkdir event-platform && cd event-platform
git init

# 2. Copy everything we generated
cp -r <downloaded-event-platform>/* .

# 3. Verify Gradle structure
# settings.gradle.kts should already include all modules
cat settings.gradle.kts

# 4. Create missing directories that Gradle expects
mkdir -p services/audit-service/src/main/java/com/platform/audit
mkdir -p services/audit-service/src/main/resources
mkdir -p services/processing-service/src/main/resources

# 5. Initial commit
git add -A
git commit -m "chore: initial project skeleton from architecture design"
```

**Create a `.github/copilot-instructions.md`** file in your repo root. Copilot reads this automatically on every interaction:

```markdown
# Copilot Project Instructions

## Project Context
This is a Kafka-based event processing platform built with:
- Java 17, Gradle (Kotlin DSL), Spring Boot 3.2.x
- Spring Kafka (batch consumers, manual ack, CooperativeStickyAssignor)
- Oracle DB (JDBC batch operations, NOT JPA/Hibernate)
- Redis (Lettuce, async, dedup cache)
- Resilience4J (circuit breaker, rate limiter, bulkhead)

## Architecture
- 3 microservices: ingestion-service, processing-service (merged processor+dispatch+retry), audit-service
- Shared library: platform-common (error codes, models, factories, Kafka config)
- Kafka topics: 10 partitions max (enterprise constraint)
- Concurrency: 5 consumer threads per pod, 2 pods per service

## Code Conventions
- Use JDBC batch operations (not JPA) for all DB writes. Never individual INSERT in hot path.
- All exceptions must carry an ErrorCode from the com.platform.common.error.ErrorCode enum.
- Every DB table uses NUMBER(19) for IDs with Oracle SEQUENCE (CACHE 1000).
- Kafka consumers use batch listener mode with manual acknowledgment.
- Use ThreadPoolTaskExecutor (50 threads) for async vendor dispatch, not @Async annotation.
- All new upstream processors implement UpstreamProcessor interface.
- All new channel adapters implement ChannelAdapter interface.
- JSON logging via Logback + MDC (correlationId, eventId, upstreamId, channel).

## Do NOT
- Do not use JPA/Hibernate. Use JdbcTemplate exclusively.
- Do not use @Async annotation. Use the injected "dispatchExecutor" bean.
- Do not use auto-commit for Kafka consumers.
- Do not create individual DB INSERTs in loops. Always batch.
- Do not hardcode vendor credentials. Use environment variables.
```

---

## Phase 1: Use PLAN Mode to Validate Understanding

**Open Copilot Chat in VS Code → Switch to Plan mode.**

### Step 1A: Feed the Architecture

In Plan mode, paste this prompt. Reference the files by path (Copilot can read workspace files):

```
I need you to understand the architecture of this event-processing platform before we 
start coding. Please read these files and confirm your understanding by summarizing:
1. The 3-service decomposition and what each service owns
2. The Kafka topic layout and why we chose 10 partitions
3. The batch processing pattern (poll 500 → async threads → batch DB write → ack)
4. The error code taxonomy and how retryable vs non-retryable errors flow differently
5. The outbox pattern and why it exists

Files to read:
- docs/ARCHITECTURE.md
- docs/ARCHITECTURE-ADDENDUM.md  
- docs/GAP-ANALYSIS.md
- docs/workflow-diagram.mermaid
- common/src/main/java/com/platform/common/error/ErrorCode.java
- schema/V1__init_schema.sql
```

**Wait for Copilot to respond.** Verify it understood correctly. If it misses something, correct it before proceeding.

### Step 1B: Generate the Implementation Plan

Still in Plan mode:

```
Now create an implementation plan with these constraints:
- Java 17, Gradle, Spring Boot 3.2.x
- We already have: project skeleton, Gradle builds, error codes, models, factory interfaces, 
  Kafka consumer config, async config, error handler config
- We need to implement in this order (each is a separate task):

Phase A: platform-common module (complete and compilable)
Phase B: ingestion-service (Kafka listener, dedup, schema validation)
Phase C: processing-service — processing layer (upstream processors, batch DB persist, outbox)
Phase D: processing-service — dispatch layer (channel adapters, async dispatch, batch DB)
Phase E: processing-service — retry layer (backoff, DLT routing)
Phase F: audit-service (batch consumer, batch persist, archival scheduler)
Phase G: integration tests (embedded Kafka + Testcontainers Oracle)

For each phase, list:
1. Exact files to create or modify
2. Dependencies on prior phases
3. What to test before moving to next phase

Save this as PLAN.md in the docs/ directory.
```

**Review the generated PLAN.md.** This is your implementation roadmap. Commit it.

---

## Phase 2: Implement with AGENT Mode (Phase by Phase)

**Switch to Agent mode.** Now you implement one phase at a time. The critical rule:

> **One phase per Agent conversation. Do not ask Agent to implement Phase B before Phase A compiles and tests pass.**

### Phase A: platform-common (Estimated: 30–45 min)

Most of this is already generated. Tell Agent to complete it:

```
Working on Phase A from docs/PLAN.md.

The platform-common module already has these files:
- ErrorCode.java, PlatformException.java, NonRetryableException.java, RetryableException.java
- EventEnvelope.java, ChannelType.java, EventStatus.java  
- UpstreamProcessor.java, UpstreamProcessorFactory.java
- ChannelAdapter.java, ChannelAdapterFactory.java
- AuditEventPublisher.java
- KafkaConsumerConfig.java, AsyncProcessingConfig.java, KafkaErrorHandlerConfig.java

Please:
1. Review each file for compilation errors against Java 17 and our build.gradle.kts dependencies
2. Add missing imports
3. Add a KafkaProducerConfig.java with idempotent transactional producer configuration
4. Add a JacksonConfig.java that configures ObjectMapper with JavaTimeModule for Instant serialization
5. Write unit tests for UpstreamProcessorFactory and ChannelAdapterFactory

After completion, verify the module compiles: ./gradlew :platform-common:build
```

**Verify**: `./gradlew :platform-common:build` passes before moving on.

### Phase B: ingestion-service (Estimated: 1–2 hours)

```
Working on Phase B from docs/PLAN.md.

Implement the ingestion-service. Key files already exist as skeletons:
- InboundKafkaListener.java
- DeduplicationService.java  
- SchemaValidationService.java
- application.yml

Please:
1. Complete InboundKafkaListener — make it use batch processing correctly with 
   the batchKafkaListenerContainerFactory from platform-common
2. Complete DeduplicationService — two-tier (Redis fast path, Oracle DB fallback).
   Use the JDBC batch approach for marking seen (not individual INSERT per message)
3. Add an InboundKafkaListenerContainerConfig that wires the error handler 
   from KafkaErrorHandlerConfig
4. Create the Spring Boot Application class  
5. Write integration test using @EmbeddedKafka that:
   a. Sends 100 messages to inbound.banking topic
   b. Verifies all 100 reach processing.events topic
   c. Sends a duplicate and verifies it's filtered
   d. Sends a malformed message and verifies it reaches dlt.events

Reference the error codes in ErrorCode.java for which exceptions to throw where.
Reference docs/workflow-diagram.mermaid Phase 1 for the exact processing steps.
```

**Verify**: Integration test passes. Commit.

### Phase C: processing-service — Processing Layer (Estimated: 2–3 hours)

```
Working on Phase C from docs/PLAN.md.

Implement the processing layer of processing-service. Already exists:
- EventProcessingService.java (skeleton)
- EventRepository.java (interface)
- EventRepositoryImpl.java (batch implementation)
- BankingUpstreamProcessor.java (example)
- OutboxPoller.java

Please:
1. Complete EventProcessingService to use JDBC batch inserts via EventRepositoryImpl.
   The flow is: consume batch from processing.events → resolve upstream processor →
   validate → normalise → batch INSERT to events table → INSERT to outbox table → ack.
   Do NOT call kafkaTemplate.send() directly. Use the outbox pattern:
   insert into event_outbox in the same transaction as events table.
2. Create InsuranceUpstreamProcessor and OtpAuthUpstreamProcessor as additional 
   upstream processors (use BankingUpstreamProcessor as template)
3. Complete the OutboxPoller to poll event_outbox and publish to dispatch.{channel} topics
4. Create application.yml for this service (reference ingestion-service as template,
   add Resilience4J config from docs/ARCHITECTURE-ADDENDUM.md)
5. Write integration test: send 50 events through processing.events, verify 50 rows 
   in events table and 50 messages published to dispatch topics via outbox

Reference schema/V1__init_schema.sql for table structure.
Reference schema/V2__outbox_table.sql for outbox table.
```

### Phase D: processing-service — Dispatch Layer (Estimated: 2–3 hours)

```
Working on Phase D from docs/PLAN.md.

Implement the dispatch layer within processing-service. Already exists:
- DispatchServiceV2.java (async batch dispatch pattern)
- DispatchBatchRepository.java
- SmsChannelAdapter.java (example)

Please:
1. Complete DispatchServiceV2 — it must:
   a. Consume batches from all dispatch.* topics
   b. Submit each record to the dispatchExecutor thread pool (50 threads)
   c. Apply Resilience4J RateLimiter and CircuitBreaker per channel
   d. Collect all DispatchOutcome results
   e. Batch-INSERT all outcomes to event_dispatches table
   f. Batch-UPDATE events.status
   g. Publish failures to retry.events
   h. Acknowledge the batch
2. Create EmailChannelAdapter, OtpChannelAdapter (use SmsChannelAdapter as template)
3. Add vendor failover logic in ChannelAdapterFactory: if primary vendor circuit breaker 
   is open, fall through to secondary vendor
4. Use WebClient (not RestTemplate) for non-blocking vendor HTTP calls
5. Test with WireMock: mock vendor API returning 200, 500, 429, and timeout scenarios

Reference the dispatch-service application.yml for Resilience4J config.
Reference docs/GAP-ANALYSIS.md sections 5 (vendor failover) and 6 (idempotent dispatch).
```

### Phase E: processing-service — Retry Layer (Estimated: 1 hour)

```
Working on Phase E from docs/PLAN.md.

Implement the retry layer within processing-service. Already exists:
- RetryService.java (skeleton with backoff calculation)
- RetryRepository.java (interface)

Please:
1. Complete RetryService — consume from retry.events, apply exponential backoff 
   with jitter, check expiry, check max retries, re-publish to dispatch.{channel} 
   or DLT
2. Implement RetryRepositoryImpl using JDBC batch operations
3. Ensure retry messages carry an idempotency key: eventId + "-" + retryCount
4. Test: publish a message to retry.events with retryCount=0, maxRetries=3,
   verify it cycles through retry 3 times then lands in dlt.events

Reference docs/workflow-diagram.mermaid Phase 4 for exact decision flow.
```

### Phase F: audit-service (Estimated: 1 hour)

```
Working on Phase F from docs/PLAN.md.

Create the audit-service from scratch:
1. Kafka batch consumer on audit.events topic (batch=1000 for higher throughput)
2. JDBC batch INSERT into audit_log table
3. @Scheduled archival job that logs partition info (actual partition detach 
   would be DBA-managed, this just flags partitions ready for archival)
4. Micrometer counters: events.ingested, events.dispatched, events.failed, events.dlt
5. application.yml, Spring Boot main class, build.gradle.kts
6. Integration test: publish 100 audit events, verify 100 rows in audit_log

This is the simplest service. Use batch INSERT pattern from EventRepositoryImpl.
Reference schema/V1__init_schema.sql for audit_log table structure.
```

### Phase G: Integration Tests (Estimated: 2–3 hours)

```
Working on Phase G from docs/PLAN.md.

Create an end-to-end integration test module that:
1. Uses Testcontainers to start Oracle XE and Redis
2. Uses @EmbeddedKafka with all required topics
3. Sends 1000 messages to inbound.banking topic
4. Verifies:
   a. 1000 rows in events table
   b. 1000 dispatch attempts in event_dispatches
   c. Audit trail entries for each stage
   d. Dedup works (re-send same 1000 messages, count stays at 1000)
   e. Retry flow works (mock vendor returning 500 for 10 messages)
   f. DLT flow works (mock vendor returning 500 forever for 5 messages with maxRetries=2)
5. Measures: total throughput, P99 latency from ingest to dispatch

Create this in a separate Gradle module: integration-tests/
```

---

## Phase 3: What to Put in Copilot Context (and What NOT To)

### Per-Phase Context Loading

| Phase | Feed to Copilot | Do NOT feed |
|-------|----------------|-------------|
| A (common) | ErrorCode.java, models, factory interfaces, build.gradle.kts | Architecture docs, SQL, diagrams |
| B (ingestion) | common module code, application.yml, workflow-diagram.mermaid Phase 1 | Dispatch code, retry code |
| C (processing) | common module, EventRepositoryImpl, schema/V1, V2, outbox pattern from GAP-ANALYSIS.md | Dispatch code |
| D (dispatch) | common module, DispatchServiceV2, SmsChannelAdapter, Resilience4J config, GAP-ANALYSIS.md #5,#6 | Ingestion code |
| E (retry) | common module, RetryService skeleton, workflow-diagram.mermaid Phase 4 | Everything else |
| F (audit) | common module, schema/V1 (audit_log table only) | Everything else |

### Why Not Feed Everything?

Copilot Agent with Claude Sonnet 4.6 has a context window that, while large, is shared between the files you reference, the conversation history, and the generated code. If you feed it all 50 files + all 5 docs, it:
- Loses focus on the specific task
- Starts "blending" patterns from different services
- Generates plausible-looking but subtly wrong code (e.g., using individual INSERT because it saw that pattern in a different file)

**Focused context = better code generation.**

---

## Phase 4: Copilot Custom Instructions vs .github/copilot-instructions.md

You have two mechanisms:

1. **`.github/copilot-instructions.md`** (created in Phase 0): Always loaded. Put project-wide rules here (Java 17, no JPA, batch everything, error code conventions).

2. **Per-prompt instructions**: Put phase-specific context in the prompt itself. This is where you reference specific files and architecture sections.

Do NOT put the entire architecture doc in `copilot-instructions.md` — it wastes context on every interaction, even when you're just asking about a single method.

---

## Phase 5: Verification Checkpoints

After each phase, before committing:

```bash
# Compile check
./gradlew :platform-common:build
./gradlew :ingestion-service:build
./gradlew :processing-service:build
./gradlew :audit-service:build

# Test check
./gradlew test

# Dependency check (no circular deps)
./gradlew dependencies --configuration compileClasspath
```

---

## Common Copilot Pitfalls to Avoid

1. **"Implement the entire processing-service"** — Too broad. Copilot will generate a monolith that's 60% correct and 40% subtly wrong. Break into layers (C, D, E).

2. **Letting Copilot choose the DB access pattern** — It defaults to JPA/Hibernate. Always explicitly say "use JdbcTemplate with batch operations" in every prompt.

3. **Not verifying Kafka config** — Copilot often generates `enable-auto-commit: true` or forgets `CooperativeStickyAssignor`. Always check the generated application.yml against our reference.

4. **Accepting generated tests without running them** — Copilot tests compile but often assert the wrong thing. Run every test and verify it actually fails when the code is broken.

5. **Asking Copilot to "fix all errors"** — If you get 10 compilation errors, fix them one by one with specific prompts. "Fix all errors" often creates new ones.

---

## Estimated Timeline

| Phase | Effort | Dependencies |
|-------|--------|-------------|
| Phase 0: Setup | 30 min | None |
| Phase 1: Plan | 30 min | Phase 0 |
| Phase A: platform-common | 45 min | Phase 0 |
| Phase B: ingestion-service | 2 hours | Phase A |
| Phase C: processing layer | 3 hours | Phase A, B |
| Phase D: dispatch layer | 3 hours | Phase C |
| Phase E: retry layer | 1 hour | Phase D |
| Phase F: audit-service | 1 hour | Phase A |
| Phase G: integration tests | 3 hours | All above |
| **Total** | **~15 hours** | |

With a single developer using Copilot Agent effectively, this is roughly 2 working days to go from architecture to a compilable, tested codebase ready for the first upstream integration.
