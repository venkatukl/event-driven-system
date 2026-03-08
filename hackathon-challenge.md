# Hackathon Challenge: Resilient Event-Driven Notification Pipeline

## Challenge Statement

Design and implement a **scalable, fault-tolerant event processing system** that ingests high-throughput messages from an upstream source via Apache Kafka, applies configurable business validation and routing, and dispatches notifications through multiple delivery channels — all while guaranteeing zero message loss, exactly-once processing semantics, and full observability.

## Problem Context

Enterprise notification platforms process thousands of messages per second across diverse upstream systems — banking alerts, OTP authentication, insurance notifications — each with unique validation rules, routing logic, and delivery requirements. The challenge lies not in sending a single message, but in building a system that remains **consistent under load**, **resilient under failure**, and **extensible without code changes** as new upstreams and channels are added.

## Scope (2-Day Sprint)

Build a working end-to-end pipeline that handles the following flow:

```
Kafka Inbound Topic → Ingestion → Validation → Processing → Dispatch → 2 Channels
                          ↓             ↓            ↓
                       Dedup        Error Codes    Retry / DLT
```

### Mandatory Requirements

1. **Kafka Consumer**: Batch-mode consumer with manual offset commit. Must not lose messages on pod restart or consumer crash.

2. **One Upstream Processor**: Implement a single upstream (e.g., Banking Transaction Alerts) with at least 5 business validation rules (recipient format, required fields, amount range, DND check, message expiry).

3. **Two Dispatch Channels**: Implement adapters for any 2 of: SMS, Email, Voice, Push, Webhook. Use mock endpoints (WireMock or similar) — no real vendor integration required.

4. **Retry with Exponential Backoff**: Failed dispatches must retry with backoff and jitter. After max retries, route to a Dead Letter Topic.

5. **Database Persistence**: Persist events, dispatch attempts, and retry history. Schema must use proper normalisation, sequences, and indexing. Demonstrate batch INSERT (not row-by-row).

6. **Error Code Taxonomy**: Define and use structured error codes across every stage (ingestion, validation, processing, dispatch, retry). Every failure must be traceable.

7. **Configuration-Driven Design**: Adding a new upstream or dispatch channel should require only a new implementation class and a configuration change — no modifications to existing framework code. Demonstrate this with a brief explanation (code not required for the second upstream).

### Stretch Goals (Bonus Points)

- Circuit breaker on dispatch (Resilience4J or equivalent)
- Transactional Outbox pattern for DB-to-Kafka consistency
- Structured JSON logging with correlation ID propagation
- Consumer lag monitoring and metrics (Micrometer / Prometheus)
- Kafka static group membership to avoid rebalancing on restart
- Idempotent dispatch (prevent duplicate SMS/email on retry)
- Priority-based routing (OTP messages processed before marketing)

## Technical Constraints

| Constraint | Value |
|-----------|-------|
| Language | Java 17 |
| Framework | Spring Boot 3.x, Spring Kafka |
| Build Tool | Gradle or Maven |
| Database | Any RDBMS (H2 for demo is acceptable; Oracle/Postgres preferred) |
| Message Broker | Apache Kafka (embedded or containerised) |
| Containerisation | Docker Compose for local stack (Kafka + DB + app) |
| Resilience | Resilience4J (optional but earns bonus) |

## Evaluation Criteria

| Criteria | Weight | What Judges Look For |
|----------|--------|---------------------|
| **System Design** | 25% | Clean service boundaries, sensible Kafka topic design, proper DB schema normalisation, and justified architectural decisions |
| **Reliability** | 25% | Zero message loss under failure (kill a consumer mid-batch — does it recover?). Retry logic correctness. DLT routing. Deduplication. |
| **Code Quality** | 20% | Clean abstractions (factory/adapter pattern), separation of concerns, meaningful error codes, no hardcoded values in business logic |
| **Extensibility** | 15% | Can a new upstream be added with just a new class + config? Can a new channel be added the same way? Demonstrate or explain. |
| **Demo & Observability** | 15% | Can you show a message flowing end-to-end? Can you show a failed message retrying and landing in DLT? Are logs structured and traceable? |

## Deliverables

1. **Working Code**: Runnable via `docker-compose up` or `./gradlew bootRun` with embedded Kafka
2. **Architecture Diagram**: One-page diagram showing components, Kafka topics, DB tables, and the flow
3. **5-Minute Demo**: Show a message flowing through the happy path AND the failure/retry path
4. **Brief Design Doc**: 1–2 pages covering key design decisions, tradeoffs made, and what you'd do differently with more time

## Getting Started

A minimal Kafka + Spring Boot 3 skeleton with Docker Compose is provided in the `hackathon-starter/` directory. Teams are free to use it, modify it, or start from scratch.

### Suggested Time Allocation

| Block | Activity | Duration |
|-------|---------|----------|
| Day 1 Morning | System design, DB schema, Kafka topic layout, error codes | 3 hours |
| Day 1 Afternoon | Kafka consumer, ingestion, validation, DB persistence | 4 hours |
| Day 2 Morning | Dispatch adapters, retry logic, DLT, stretch goals | 4 hours |
| Day 2 Afternoon | Integration testing, demo prep, design doc | 3 hours |

## What This Challenge Tests

This isn't about building a CRUD app with Kafka. It tests whether a team can reason about:
- **Distributed system failure modes**: What happens when Kafka commit fails after DB write? When the vendor is down? When a consumer restarts mid-batch?
- **Throughput vs correctness tradeoffs**: Batch processing for speed vs per-message error handling for correctness.
- **Extensibility through patterns**: Factory, adapter, strategy — not as textbook exercises, but as practical solutions to "how do I add upstream #2 without touching existing code?"
- **Operational readiness**: Structured logs, error codes, and metrics aren't afterthoughts — they're what make a system supportable in production.

---

*Good luck. Build something you'd trust to send your own bank alerts through.*
