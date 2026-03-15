
***

## 3. `.github/copilot-instructions.md`

```markdown
# GitHub Copilot Repository Instructions

## Project Overview

This repository implements an event-driven notification system using Spring Boot 3.x, Kafka, Oracle, Redis, and Resilience4J. Upstream systems publish alerts to multiple Kafka topics. A stateless Alert Orchestrator consumes these topics, applies system-specific business logic, and routes messages to channel-specific topics. Channel Dispatcher services then deliver notifications through SMS, Email, OTP, Voice, and Push.

The goal is to support ~10k messages per second with horizontal scalability and strong resilience.

## Architecture Expectations

- Microservices:
  - `alert-orchestrator-service`:
    - Consumes multiple upstream Kafka topics.
    - Uses a pluggable `AlertProcessor` abstraction per upstream system.
    - Normalizes and enriches messages using Oracle and Redis.
    - Publishes messages to per-channel topics and audit topics.
  - Channel dispatchers (`sms-dispatcher-service`, `email-dispatcher-service`, `otp-dispatcher-service`, `voice-dispatcher-service`, `push-dispatcher-service`):
    - Consume channel-specific Kafka topics.
    - Call external providers with Resilience4J (retry, circuit-breaker, bulkhead).
    - Publish audit and retry/DLQ events.
  - Optional `audit-service`:
    - Consumes audit/DLQ topics.
    - Persists audit records to Oracle and exposes reporting.

- Kafka:
  - Multiple upstream topics (one or more per upstream system).
  - Per-channel topics for SMS, Email, OTP, Voice, Push.
  - Retry topics per channel and a shared DLQ topic.
  - Topic names and partition counts are defined in `docs/architecture.md`.

## Design Rules for Copilot

When generating or editing code:

1. **Respect the architecture docs**
   - Read and follow `docs/architecture.md`.
   - Follow the package/module structure described there and in the suggestions below.

2. **Orchestrator rules**
   - Implement a central `AlertOrchestrator` or equivalent service that:
     - Is stateless.
     - Uses Spring Kafka consumers (`@KafkaListener`) for multiple upstream topics.
     - Dispatches to a pluggable `AlertProcessor` interface per upstream system.
   - Do not hard-code per-system logic directly in listeners; use strategy/factory patterns instead.

3. **Channel dispatcher rules**
   - Each dispatcher service must:
     - Consume from its channel-specific Kafka topic.
     - Isolate external provider-specific logic behind interfaces.
     - Use Resilience4J for:
       - Retry with backoff.
       - Circuit breaker.
       - Bulkhead limits where appropriate.
   - Do not bypass Resilience4J when calling external providers.

4. **Configuration-driven behavior**
   - Prefer configuration (YAML, ConfigMaps) over hard-coded constants for:
     - Topic names.
     - Upstream system mappings.
     - Channel defaults and provider settings.
     - Retry counts and backoff policies.
   - Use configuration properties classes (`@ConfigurationProperties`) instead of scattering `@Value` all over.

5. **Persistence and data access**
   - Use Spring Data JPA for Oracle where possible.
   - Keep entities decoupled from Kafka payload DTOs.
   - All DB access must be in repository/service classes, not controllers or Kafka listeners.

6. **Error handling and logging**
   - Use a consistent error code taxonomy (e.g., `INGEST_VALIDATION_ERROR`, `DISPATCH_PROVIDER_ERROR`).
   - Logging must be structured and include:
     - `messageId` (if available).
     - `sourceSystem`.
     - `channel`.
     - `errorCode` (for errors).
   - Avoid logging sensitive data.

7. **Testing**
   - Prefer JUnit 5 + Mockito (or Spring Boot test slices) for unit/integration tests.
   - For Kafka logic, write tests using embedded Kafka or Spring Kafka test support.

8. **General coding standards**
   - Java 17+.
   - Spring Boot 3.x idioms (e.g., use `jakarta.*` annotations).
   - Avoid deprecated Spring APIs.
   - Keep methods small and single-responsibility.
   - Favor constructor injection.

## How to Use Copilot Modes

- **Plan mode**:
  - Use for multi-step refactoring or large feature additions.
  - Example prompt: “Create the initial skeleton for alert-orchestrator-service and configure Kafka consumers for upstream topics as defined in docs/architecture.md.”
  - Review and adjust the plan before allowing implementation.

- **Agent / Edit modes**:
  - Use Agent mode to implement an approved plan with multiple file edits.
  - Use Edit mode for localized changes or refactors in a single file or small set of files.

## Out of Scope / Anti-Patterns

- Do not introduce synchronous HTTP-based coupling between microservices for core workflows; rely on Kafka events.
- Do not embed complex business logic in controllers or Kafka listeners.
- Do not introduce new technologies outside the stack (e.g., RabbitMQ) unless explicitly requested.

