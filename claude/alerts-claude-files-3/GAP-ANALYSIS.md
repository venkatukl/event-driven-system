# Gap Analysis — Missing Patterns & Industry Lessons Learnt

## Java 17 + Gradle Notes

### What Changes
- **Virtual threads**: NOT available on Java 17. The addendum's suggestion to use `Executors.newVirtualThreadPerTaskExecutor()` is a future migration path (Java 21+). For now, use bounded `ThreadPoolTaskExecutor` with 50 platform threads.
- **Record classes**: Fully supported (arrived in Java 16). `DispatchOutcome`, `DispatchResult` are fine.
- **Text blocks** (`"""`): Fully supported (Java 15+). All SQL strings use them.
- **Sealed classes**: Available but not used in this design. No change needed.
- **Spring Boot 3.x**: Requires Java 17 minimum. Compatible.

### Gradle Build Setup
Each microservice should have its own `build.gradle.kts` with a dependency on `platform-common` as an included build or published artifact. See `build.gradle.kts` files in the project.

---

## Missing Patterns & Considerations

### 1. OUTBOX PATTERN (Critical — Currently Missing)

**Problem**: The processing service does two things in sequence: INSERT into Oracle, then publish to Kafka. If the Kafka publish fails after the DB commit, you have an event persisted but never dispatched — a silent data loss.

**Solution**: Transactional Outbox pattern.

```
Instead of:
  BEGIN TX → INSERT events → COMMIT → kafkaTemplate.send()  ← can fail here

Do:
  BEGIN TX → INSERT events → INSERT outbox → COMMIT
  (separate thread) → Poll outbox → kafkaTemplate.send() → DELETE outbox row
```

The outbox table guarantees that if the DB commit succeeds, the Kafka message WILL eventually be published. This is the industry standard for DB-to-Kafka consistency.

```sql
CREATE TABLE event_outbox (
    outbox_id       NUMBER(19)    DEFAULT seq_outbox_id.NEXTVAL PRIMARY KEY,
    aggregate_id    NUMBER(19)    NOT NULL,     -- event_id
    topic           VARCHAR2(128) NOT NULL,     -- target Kafka topic
    partition_key   VARCHAR2(128),              -- Kafka message key
    payload         CLOB          NOT NULL,     -- serialised EventEnvelope
    status          VARCHAR2(10)  DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    created_at      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
    sent_at         TIMESTAMP
);

CREATE INDEX idx_outbox_pending ON event_outbox (status, created_at)
    WHERE status = 'PENDING';
```

A `@Scheduled` poller (or Debezium CDC connector) reads PENDING rows and publishes to Kafka. After successful send, marks as SENT. Batch-deletes SENT rows older than 1 hour.

### 2. POISON PILL HANDLING (Currently Partial)

**Problem**: A malformed message that causes an exception on every deserialization attempt will be retried infinitely (consumer seeks back to the same offset).

**Solution**: Spring Kafka `ErrorHandler` with `DeadLetterPublishingRecoverer`:

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(template,
            (record, ex) -> new TopicPartition("dlt.events", -1));

    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
        new FixedBackOff(1000L, 2));  // 2 retries at consumer level

    // Non-retryable exceptions skip straight to DLT
    handler.addNotRetryableExceptions(
        NonRetryableException.class,
        com.fasterxml.jackson.core.JsonParseException.class,
        org.apache.kafka.common.errors.SerializationException.class
    );
    return handler;
}
```

### 3. CONSUMER LAG MONITORING & AUTO-SCALING TRIGGER (Partially Addressed)

**Problem**: If a vendor slows down, consumer lag builds up. The current HPA uses CPU utilisation, which may not spike even as lag grows.

**Solution**: Use Kafka consumer lag as a custom HPA metric:

```yaml
# Prometheus query for consumer lag
kafka_consumergroup_lag_sum{consumergroup="dispatch-group"} > 10000

# KEDA ScaledObject (alternative to HPA for Kafka-aware scaling)
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: dispatch-scaler
spec:
  scaleTargetRef:
    name: processor-service
  minReplicaCount: 2
  maxReplicaCount: 10
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: kafka:9092
        consumerGroup: dispatch-group
        topic: dispatch.sms
        lagThreshold: "5000"
```

### 4. MESSAGE ORDERING GUARANTEES (Not Addressed)

**Problem**: If the same recipient receives two messages (e.g., "OTP: 1234" then "OTP: 5678"), out-of-order delivery could send the old OTP after the new one.

**Solution**: Use recipient hash as the Kafka message key:

```java
// In processing service, when publishing to dispatch topic:
String partitionKey = DigestUtils.sha256Hex(envelope.getRecipient());
kafkaTemplate.send(dispatchTopic, partitionKey, json);
```

Same recipient always goes to the same partition → same consumer thread → ordered processing. This is critical for OTP and transaction alerts.

### 5. GRACEFUL DEGRADATION / FALLBACK VENDOR (Partially Addressed)

**Problem**: If Twilio is down (circuit breaker open), SMS messages pile up in retry. There's no automatic failover to a backup vendor.

**Solution**: Vendor failover chain in `ChannelAdapterFactory`:

```java
public ChannelAdapter getAdapter(ChannelType channel) {
    List<ChannelAdapter> adapters = adaptersByChannel.get(channel);  // ordered by priority
    for (ChannelAdapter adapter : adapters) {
        String vendorKey = channel.name() + "-" + adapter.getVendorName();
        CircuitBreaker cb = cbRegistry.circuitBreaker(vendorKey);
        if (cb.getState() != CircuitBreaker.State.OPEN) {
            return adapter;
        }
    }
    throw new RetryableException(ErrorCode.DSP_CIRCUIT_001, null);
}
```

This means: try Twilio first, if Twilio's circuit breaker is open, fall through to MSG91 automatically. No retry hop needed.

### 6. IDEMPOTENT DISPATCH (Currently Missing)

**Problem**: Retry logic re-publishes to `dispatch.<channel>`. If the vendor API succeeded but the Kafka commit failed, the message gets dispatched twice (duplicate SMS/email to user).

**Solution**: Idempotency key on vendor API calls:

```java
// Generate a stable dispatch idempotency key
String idempotencyKey = envelope.getEventId() + "-" + envelope.getRetryCount();

// Send as HTTP header to vendor
headers.set("Idempotency-Key", idempotencyKey);
// Twilio: headers.set("X-Twilio-Idempotency-Key", idempotencyKey);
// SendGrid: dedup via custom_args
```

Most enterprise vendors support idempotency keys. If not, check `event_dispatches` table for existing SENT record before calling the vendor.

### 7. PRIORITY QUEUE / WEIGHTED CONSUMPTION (Not Addressed)

**Problem**: OTP messages (priority 1) and marketing messages (priority 7) share the `dispatch.sms` topic. A flood of marketing messages can starve OTP delivery.

**Solutions** (pick one):

**A. Separate topics by priority** (simplest):
```
dispatch.sms.high    (OTP, banking — priority 1-3)
dispatch.sms.low     (marketing — priority 4-9)
```
Configure more consumer threads on the high-priority topic.

**B. Weighted consumer** (more complex):
```java
// Poll high-priority topic 3× more frequently than low
@Scheduled(fixedDelay = 100)   // every 100ms
void pollHighPriority() { consumer.poll("dispatch.sms.high"); }

@Scheduled(fixedDelay = 300)   // every 300ms
void pollLowPriority() { consumer.poll("dispatch.sms.low"); }
```

### 8. CONFIGURATION HOT-RELOAD WITHOUT RESTART (Partially Addressed)

**Problem**: Adding a new upstream requires a pod restart to pick up the new Kafka consumer topic.

**Solutions**:
- **Spring Cloud Config** + `@RefreshScope` for non-Kafka config (upstream rules, vendor endpoints).
- **Kafka topic pattern subscription** (`topicPattern = "inbound\\..*"`) — already in our code. New topics matching the pattern are picked up on the next metadata refresh (`metadata.max.age.ms`, default 5 min). No restart needed for new inbound topics.
- For truly zero-downtime: use `KafkaListenerEndpointRegistry` to programmatically register new listeners at runtime.

### 9. DATA MASKING IN LOGS & AUDIT (Currently Missing)

**Problem**: Logging `recipient` (phone/email) and `payload` (may contain PII) violates GDPR/data protection.

**Solution**: Custom Logback `MaskingPatternLayout`:

```java
public class PiiMaskingLayout extends PatternLayout {
    private static final Pattern PHONE = Pattern.compile("(\\+?\\d{2})\\d+(\\d{4})");
    private static final Pattern EMAIL = Pattern.compile("(\\w{2})[^@]+(@\\w+)");

    @Override
    public String doLayout(ILoggingEvent event) {
        String msg = super.doLayout(event);
        msg = PHONE.matcher(msg).replaceAll("$1****$2");   // +91****7890
        msg = EMAIL.matcher(msg).replaceAll("$1****$2");    // jo****@gmail
        return msg;
    }
}
```

Also encrypt PII columns in Oracle using application-level AES-256-GCM (not just TDE).

### 10. HEALTH CHECK BEYOND LIVENESS (Currently Basic)

**Problem**: Pod is "live" (JVM running) but Kafka consumer is stuck (no heartbeat) or DB pool is exhausted. OCP won't restart it.

**Solution**: Custom health indicators:

```java
@Component
public class KafkaConsumerHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Check if consumer has polled within max.poll.interval.ms
        if (lastPollTimestamp < System.currentTimeMillis() - maxPollIntervalMs) {
            return Health.down().withDetail("reason", "Consumer stalled").build();
        }
        return Health.up().build();
    }
}
```

Wire into readiness probe (not liveness — don't restart on transient DB issues).

### 11. DISTRIBUTED TRACING (Currently MDC Only)

**Problem**: MDC gives per-service correlation, but you can't trace a message across all 3 services visually.

**Solution**: OpenTelemetry with Kafka header propagation:

```groovy
// build.gradle.kts
implementation("io.opentelemetry:opentelemetry-api:1.32.0")
implementation("io.opentelemetry.instrumentation:opentelemetry-kafka-clients-2.6:1.32.0-alpha")
```

Spring Boot 3.x has built-in Micrometer Tracing support. Add `management.tracing.sampling.probability=1.0` and traces propagate via Kafka headers automatically.

### 12. SCHEMA EVOLUTION & VERSIONING (Not Addressed)

**Problem**: Upstream changes their message format. Old consumers can't parse new messages.

**Solution**: Schema Registry (Confluent or Apicurio) with BACKWARD compatibility:

```yaml
# If enterprise Kafka includes schema registry:
spring.kafka.consumer.properties:
  schema.registry.url: http://schema-registry:8081
  value.deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
  specific.avro.reader: true
```

If schema registry isn't available: use a `version` field in the JSON envelope and version-specific deserializers.

### 13. DB CONNECTION LEAK DETECTION (Operational)

```yaml
spring.datasource.hikari:
  leak-detection-threshold: 30000   # log warning if connection held > 30s
  connection-test-query: "SELECT 1 FROM DUAL"
  validation-timeout: 5000
```

### 14. KAFKA PRODUCER CALLBACK ERROR HANDLING (Currently Fire-and-Forget)

```java
// Current: kafkaTemplate.send(topic, key, value)  ← ignores send failures

// Should be:
kafkaTemplate.send(topic, key, value).whenComplete((result, ex) -> {
    if (ex != null) {
        log.error("Kafka send failed: topic={}, key={}", topic, key, ex);
        // Persist to outbox table for retry (see pattern #1)
    }
});
```

### 15. LOAD SHEDDING (Not Addressed)

**Problem**: If all 5 upstreams send 5K msg/s simultaneously (25K/s), but one vendor is down, the retry topic floods and dispatch consumers can't keep up.

**Solution**: Per-upstream rate limiting at ingestion:

```java
// In InboundKafkaListener, before processing:
RateLimiter upstreamLimiter = rateLimiterRegistry.rateLimiter(upstreamId);
if (!upstreamLimiter.acquirePermission()) {
    // Pause this consumer's partitions temporarily
    consumer.pause(consumer.assignment());
    Thread.sleep(1000);
    consumer.resume(consumer.assignment());
    return;
}
```

This prevents a misbehaving upstream from flooding the entire pipeline.

---

## Summary: Priority-Ranked Missing Items

| Priority | Pattern | Risk Without It | Effort |
|----------|---------|----------------|--------|
| P0 | Outbox pattern | Silent message loss on Kafka failure | Medium |
| P0 | Idempotent dispatch | Duplicate SMS/email to users | Low |
| P0 | Poison pill handling | Consumer stuck in infinite loop | Low |
| P1 | Message ordering (recipient key) | Out-of-order OTP delivery | Low |
| P1 | Vendor failover chain | Extended outage when primary vendor down | Medium |
| P1 | PII masking in logs | GDPR/compliance violation | Low |
| P1 | Priority queues (high/low) | OTP starved by marketing flood | Low |
| P2 | Consumer lag HPA / KEDA | Slow auto-scaling response | Medium |
| P2 | Schema evolution | Breaking changes crash consumers | Medium |
| P2 | Distributed tracing | Blind spots in message journey | Low |
| P2 | Load shedding | Upstream flood kills pipeline | Medium |
| P3 | Hot-reload (runtime listener) | Pod restart for new upstream | High |
| P3 | Health indicators (Kafka/DB) | Silent consumer stalls | Low |
| P3 | Producer callback handling | Undetected Kafka send failures | Low |
| P3 | DB leak detection | Connection pool exhaustion | Config only |
