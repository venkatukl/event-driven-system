
***

## 2. `docs/architecture.md`

```markdown
# Architecture

## 1. Goals and Requirements

- Process up to ~10k messages per second across multiple upstream systems.
- All upstream systems publish to Kafka (multiple topics).
- Stateless, horizontally scalable services deployed on OCP.
- Strong resilience: no message loss, controlled retries, DLQ, and robust error codes.
- Configuration-driven and pluggable: adding a new upstream system or channel should mostly involve configuration plus a small, isolated handler implementation.

## 2. Bounded Contexts and Services

### 2.1 Alert Orchestrator Service

**Responsibilities**

- Consume alerts from multiple upstream Kafka topics (one or more per upstream system).
- Normalize events into an internal alert representation.
- Enrich alerts using Oracle (user data, preferences, templates) and Redis cache.
- Apply per-upstream business rules via pluggable processors.
- Determine the target channels (SMS, Email, OTP, Voice, Push) and routing.
- Persist core alert state and transitions.
- Publish channel-specific messages to per-channel Kafka topics.
- Publish audit events (status changes, errors) to audit topics.

**Key Design Points**

- Stateless; no in-memory long-lived state for message lifecycle.
- Uses a factory/strategy pattern:
  - `AlertProcessor` / `AlertHandler` per upstream system.
  - Config-driven mapping of Kafka topic → handler → channel rules.
- One consumer group (`alert-orchestrator-group`) across multiple orchestrator pods for parallelism.

### 2.2 Channel Dispatcher Services

Each channel dispatcher is a separate microservice (or at least a separate deployable):

- `sms-dispatcher-service`
- `email-dispatcher-service`
- `otp-dispatcher-service`
- `voice-dispatcher-service`
- `push-dispatcher-service`

**Responsibilities**

- Consume channel-specific Kafka topics (e.g., `alerts.sms`).
- Optionally read additional data (user preferences, templates) from Redis/Oracle.
- Integrate with one or more external providers for that channel.
- Apply rate-limiting and prioritization if needed.
- Use Resilience4J for retry, circuit breaker, bulkhead per provider.
- Publish delivery status and error details to `alerts.audit` and/or `alerts.<channel>.retry.*` / `alerts.dlq`.

### 2.3 Audit Service

**Responsibilities**

- Consume `alerts.audit` and `alerts.dlq`.
- Persist message lifecycle, status, and errors to Oracle (audit tables).
- Expose APIs for searching and reporting on message flows.
- May also offer ad-hoc DLQ replay/retry tools.

In an initial implementation, audit consumption may be handled inside the orchestrator and later extracted into its own service.

## 3. Kafka Design

### 3.1 Topics

- Upstream input topics:
  - `alerts.systemA.input`
  - `alerts.systemB.input`
  - `alerts.systemC.input`
  - ...
- Internal / normalized topic (optional):
  - `alerts.normalized`
- Per-channel topics:
  - `alerts.sms`
  - `alerts.email`
  - `alerts.otp`
  - `alerts.voice`
  - `alerts.push`
- Retry topics:
  - `alerts.sms.retry.1`, `alerts.sms.retry.2`, ...
  - Similar per channel
- DLQ:
  - `alerts.dlq`
- Audit:
  - `alerts.audit`

### 3.2 Partitioning and Keys

- Upstream topics:
  - High partition count (e.g., 64–256) for parallelism.
  - Key by `messageId`, `userId`, or `tenantId` for ordering where needed.
- Channel topics:
  - Partition count sized to expected throughput per channel.
- Consumer groups:
  - Orchestrator: `alert-orchestrator-group`.
  - SMS: `sms-dispatcher-group`.
  - Email: `email-dispatcher-group`.
  - etc.

## 4. Processing Flow (Happy Path)

1. Upstream system publishes a message to its dedicated input topic (e.g., `alerts.systemA.input`).
2. Alert Orchestrator consumes the message and:
   - Uses topic name and/or metadata to select an `AlertProcessor`.
   - Validates and normalizes the message.
   - Enriches from Oracle/Redis (user details, preferences, templates).
   - Decides which channels to use and constructs channel-specific payloads.
   - Persists the core alert record.
   - Publishes channel-specific messages to the appropriate channel topics.
   - Publishes an audit event for the transition.

3. Each Channel Dispatcher service consumes its channel topic:
   - Optionally enriches from cache/DB.
   - Sends the notification to the configured provider(s) with resilience patterns.
   - Publishes success/failure audit events.
   - On failure:
     - Uses retry topics for transient errors.
     - Sends to DLQ for permanent failures or exhausted retries.

4. Audit Service consumes audit and DLQ topics and persists entries in Oracle.

## 5. Resilience Patterns

- Resilience4J for:
  - Retries with backoff for external provider calls.
  - Circuit breakers per provider.
  - Bulkheads and rate-limits as needed.
- Kafka-based retry:
  - Retry topics per channel with increasing backoff.
  - DLQ topic for messages that cannot be processed successfully.
- Idempotency:
  - Idempotent keys for provider calls (where supported).
  - Idempotent processing at orchestrator and dispatcher level (messageId-based).

## 6. Data Model (High Level)

Core Oracle tables (names indicative):

- `ALERT`:
  - `id`, `source_system`, `source_topic`, `message_key`, `raw_payload`, `status`, `created_at`, `updated_at`.
- `ALERT_CHANNEL`:
  - `id`, `alert_id`, `channel_type`, `provider`, `status`, `last_error_code`, `retry_count`, `created_at`, `updated_at`.
- `ALERT_AUDIT`:
  - `id`, `alert_id`, `channel_type`, `event_type`, `status`, `error_code`, `error_message`, `timestamp`, `metadata`.

User data and templates can live in separate tables, cached in Redis for fast access.

## 7. Configuration and Pluggability

- Mapping of upstream topics to processors and rules defined in YAML (ConfigMaps in OCP):
  - Example:
    ```yaml
    orchestrator:
      upstreams:
        systemA:
          topic: alerts.systemA.input
          processorBean: systemAAlertProcessor
          defaultChannels: [SMS, EMAIL]
        systemB:
          topic: alerts.systemB.input
          processorBean: systemBAlertProcessor
          defaultChannels: [PUSH]
    ```
- Channel-specific configuration (providers, rate limits, retries) is also YAML-driven:
  ```yaml
  channels:
    sms:
      providers:
        - name: primarySmsProvider
          baseUrl: ...
          timeoutMs: 2000
          maxRetries: 3
    email:
      providers:
        - name: primaryEmailProvider
          ...
