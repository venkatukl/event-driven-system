# Architecture Revision — 10 Upstreams, 5 Microservices, Priority Queues, Observability

## Revision Summary

| Change | Previous | Revised |
|--------|----------|---------|
| Upstreams | 5–6 | 10 |
| Aggregate throughput | 25–30K msg/s | 50K msg/s |
| Microservices | 3 (merged processor) | 5 (separate dispatch + retry) |
| Priority handling | Single dispatch topic per channel | Tiered topics (high/medium/low) |
| Observability | Splunk logs + basic Prometheus | Full metrics pipeline + real-time dashboards |

---

## 1. Revised 5-Microservice Architecture

### Why Split Back to 5

At 50K msg/s, the merged processor-service was handling three distinct workload profiles in one JVM:

- **Processing**: CPU-bound (validation, normalization, JSON parsing). Needs fast throughput.
- **Dispatch**: I/O-bound (vendor HTTP calls, 50-500ms each). Needs large async thread pool.
- **Retry**: Timer-bound (backoff scheduling, DLT decisions). Low throughput, high precision.

These compete for the same resources. A slow vendor API consuming all dispatch threads causes processing to back up because they share the same pod's CPU and memory. Splitting them gives each service its own resource pool, scaling profile, and failure domain.

### Revised Service Topology

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         KAFKA CLUSTER                                       │
│  inbound.* (10) │ processing.* (10) │ dispatch.*.{high|med|low}            │
│  retry.events   │ dlt.events        │ audit.events                         │
└───┬─────────┬────────────┬────────────────┬──────────────┬─────────────────┘
    │         │            │                │              │
    ▼         ▼            ▼                ▼              ▼
┌────────┐ ┌──────────┐ ┌──────────────┐ ┌──────────┐ ┌──────────┐
│INGEST  │ │PROCESSING│ │  DISPATCH    │ │  RETRY   │ │  AUDIT   │
│SERVICE │ │ SERVICE  │ │  SERVICE     │ │ SERVICE  │ │ SERVICE  │
│        │ │          │ │              │ │          │ │          │
│2 pods  │ │3 pods    │ │3-5 pods      │ │2 pods    │ │2 pods    │
│conc=5  │ │conc=5    │ │conc=5        │ │conc=3    │ │conc=5    │
│        │ │          │ │50 async thds │ │          │ │          │
└────────┘ └──────────┘ └──────────────┘ └──────────┘ └──────────┘
```

### Service Responsibilities (Revised)

| # | Service | Pods | Concurrency | What It Owns |
|---|---------|------|-------------|-------------|
| 1 | **ingestion-service** | 2 | 5 | Consume inbound.*, dedup, schema validate, enrich, publish to processing.* |
| 2 | **processing-service** | 3 | 5 | Upstream processor factory, business validation, normalization, batch INSERT to events, outbox publish to dispatch topics |
| 3 | **dispatch-service** | 3–5 (HPA) | 5 + 50 async threads | Consume dispatch.*.{high/med/low}, channel adapter factory, Resilience4J (circuit breaker + rate limiter), async vendor calls, batch INSERT to event_dispatches, publish failures to retry.events |
| 4 | **retry-service** | 2 | 3 | Consume retry.events, exponential backoff, expiry check, max-retry enforcement, re-publish to dispatch or DLT |
| 5 | **audit-service** | 2 | 5 | Consume audit.events, batch INSERT to audit_log, populate upstream_metrics_hourly, emit Prometheus metrics, partition archival |

### Why Dispatch Gets HPA (3–5 Pods)

Dispatch is the only service with unpredictable latency — vendor APIs can spike from 50ms to 2 seconds during peak hours. HPA scales dispatch pods based on consumer lag:

```yaml
# KEDA or HPA custom metric
triggers:
  - type: kafka
    metadata:
      consumerGroup: dispatch-group
      lagThreshold: "5000"  # scale up when lag > 5K per pod
```

Processing and ingestion have predictable, CPU-bound workloads — fixed pod count is sufficient.

### Resource Budget (Revised)

| Service | CPU (request/limit) | Memory (request/limit) | DB Pool | Redis Pool |
|---------|--------------------|-----------------------|---------|------------|
| ingestion-service | 500m / 2000m | 1Gi / 2Gi | 15 | 16 |
| processing-service | 1000m / 3000m | 1.5Gi / 3Gi | 25 | 8 |
| dispatch-service | 1000m / 3000m | 1.5Gi / 3Gi | 30 | 8 |
| retry-service | 250m / 1000m | 512Mi / 1Gi | 10 | 0 |
| audit-service | 500m / 2000m | 1Gi / 2Gi | 20 | 0 |
| **Total (min pods)** | **6250m** | **10.5Gi** | **165 conns** | **48 conns** |
| **Total (max pods)** | **12750m** | **21.5Gi** | **285 conns** | **80 conns** |

---

## 2. Priority-Based Processing

### The Problem

Without priority handling, a Marketing campaign sending 10K msg/s of priority-7 bulk SMS shares the `dispatch.sms` topic with Banking OTP messages at priority 1. Kafka topics are FIFO within a partition — there's no way to "skip ahead" to higher-priority messages. During the campaign, OTP delivery latency spikes from 200ms to 5+ seconds because OTP messages wait behind thousands of marketing messages in the same partition queue.

### The Solution: Tiered Dispatch Topics

Split each dispatch channel into 3 priority tiers:

```
BEFORE (flat):
  dispatch.sms          ← OTP + Banking + Marketing all mixed

AFTER (tiered):
  dispatch.sms.high     ← OTP, Fraud Alerts (priority 1-3)
  dispatch.sms.medium   ← Banking txn, Insurance (priority 4-6)
  dispatch.sms.low      ← Marketing, Logistics (priority 7-9)
```

The processing service routes to the correct tier based on the event's resolved priority:

```java
// In processing service, after normalization:
String tier = switch (envelope.getPriority()) {
    case 1, 2, 3 -> "high";
    case 4, 5, 6 -> "medium";
    default -> "low";
};
String topic = "dispatch." + envelope.getChannel().name().toLowerCase() + "." + tier;
outboxRepository.insert(envelope.getEventId(), topic, corrId, json);
```

### Consumer Weighting

The dispatch service allocates more consumer threads to high-priority topics:

```yaml
# dispatch-service application.yml

platform.dispatch:
  tier-config:
    high:
      concurrency: 5        # 5 threads dedicated to high priority
      poll-interval-ms: 50   # poll every 50ms (aggressive)
    medium:
      concurrency: 3        # 3 threads for medium
      poll-interval-ms: 100
    low:
      concurrency: 2        # 2 threads for low (background)
      poll-interval-ms: 200  # poll less frequently
```

At 10 consumer threads total across tiers, high-priority messages get 50% of dispatch capacity even though they're typically < 20% of volume. This guarantees OTP messages are processed before bulk marketing.

### Revised Kafka Topic Layout (10 Upstreams, Priority Tiers)

| Topic | Partitions | Tier | Retention |
|-------|-----------|------|-----------|
| inbound.banking | 10 | — | 7 days |
| inbound.insurance | 10 | — | 7 days |
| inbound.otp-auth | 10 | — | 7 days |
| inbound.marketing | 10 | — | 7 days |
| inbound.logistics | 10 | — | 7 days |
| inbound.payments | 10 | — | 7 days |
| inbound.rewards | 10 | — | 7 days |
| inbound.kyc | 10 | — | 7 days |
| inbound.fraud | 10 | — | 7 days |
| inbound.support | 10 | — | 7 days |
| processing.events | 10 | — | 3 days |
| dispatch.sms.high | 10 | HIGH | 1 day |
| dispatch.sms.medium | 10 | MEDIUM | 3 days |
| dispatch.sms.low | 10 | LOW | 3 days |
| dispatch.email.high | 10 | HIGH | 1 day |
| dispatch.email.medium | 10 | MEDIUM | 3 days |
| dispatch.email.low | 10 | LOW | 3 days |
| dispatch.otp.high | 6 | HIGH | 1 day |
| dispatch.voice.high | 4 | HIGH | 3 days |
| dispatch.voice.medium | 4 | MEDIUM | 3 days |
| dispatch.webhook.medium | 10 | MEDIUM | 3 days |
| dispatch.webhook.low | 10 | LOW | 3 days |
| dispatch.push.high | 10 | HIGH | 1 day |
| dispatch.push.medium | 10 | MEDIUM | 3 days |
| dispatch.push.low | 10 | LOW | 3 days |
| retry.events | 6 | — | 7 days |
| dlt.events | 4 | — | 30 days |
| audit.events | 10 | — | 14 days |

Note: Not every channel needs all 3 tiers. OTP is inherently high-priority only. Voice rarely has low-priority use cases. The tier configuration is driven by `upstream_config` and `channel_vendor_config`.

### Priority Assignment (Config-Driven)

Priority is assigned at two levels:

**Level 1 — Upstream default** (from `upstream_config.priority`):

```sql
-- OTP and Fraud always high priority
UPDATE upstream_config SET priority = 1 WHERE upstream_id IN ('OTP_AUTH', 'FRAUD');
UPDATE upstream_config SET priority = 3 WHERE upstream_id = 'BANKING';
UPDATE upstream_config SET priority = 5 WHERE upstream_id IN ('INSURANCE', 'PAYMENTS', 'KYC');
UPDATE upstream_config SET priority = 7 WHERE upstream_id IN ('MARKETING', 'REWARDS');
UPDATE upstream_config SET priority = 5 WHERE upstream_id IN ('LOGISTICS', 'SUPPORT');
```

**Level 2 — Event-type override** (in upstream processor logic):

```java
// In BankingUpstreamProcessor.normalise():
if ("FRAUD_ALERT".equals(payload.get("type"))) {
    envelope.setPriority(1);  // override upstream default of 3
} else if ("STATEMENT".equals(payload.get("type"))) {
    envelope.setPriority(6);  // lower than default for non-urgent
}
```

---

## 3. Observability Architecture

### The Problem with Splunk-Only

Splunk is excellent for log search and investigation (after something goes wrong). It is poor for:
- **Real-time dashboards**: Splunk dashboards have 30–60 second refresh delays. You need sub-second visibility during incidents.
- **Metric aggregation**: "How many messages per second are in RETRYING state right now?" requires counting log lines — expensive and slow at 50K msg/s.
- **Alerting on thresholds**: Splunk alerts on log patterns, not on metric values. "Alert when dispatch.sms.high consumer lag exceeds 5000" is not a log pattern — it's a metric query.
- **Long-term trend analysis**: Storing 50K metrics-per-second in Splunk is prohibitively expensive.

### Observability Stack

```
┌──────────────────────────────────────────────────────────────────┐
│                    OBSERVABILITY LAYER                            │
│                                                                  │
│  ┌──────────┐   ┌──────────────┐   ┌──────────┐   ┌──────────┐ │
│  │Prometheus │   │   Grafana    │   │  Splunk  │   │PagerDuty │ │
│  │ (Metrics) │──▶│ (Dashboards) │   │  (Logs)  │   │ (Alerts) │ │
│  └────▲─────┘   └──────────────┘   └────▲─────┘   └────▲─────┘ │
│       │                                  │              │        │
│       │ Scrape /actuator/prometheus       │ Fluentd      │ Rules  │
│       │ every 15s                        │ sidecar      │        │
└───────┼──────────────────────────────────┼──────────────┼────────┘
        │                                  │              │
   ┌────┴─────────────────────────────────┴──────────────┘
   │            ALL 5 MICROSERVICES
   │
   │  Micrometer Metrics:                  Structured Logs (JSON):
   │  ├─ event.received.count              ├─ correlationId
   │  ├─ event.validated.count             ├─ eventId
   │  ├─ event.dispatched.count            ├─ upstreamId
   │  ├─ event.failed.count                ├─ channel
   │  ├─ event.retry.count                 ├─ errorCode
   │  ├─ event.dlt.count                   ├─ stage
   │  ├─ vendor.api.latency               ├─ status
   │  ├─ vendor.api.success.rate           └─ responseTimeMs
   │  ├─ circuit.breaker.state
   │  ├─ kafka.consumer.lag
   │  ├─ db.batch.insert.duration
   │  └─ upstream.quota.utilization
   │
   │  Tags on every metric:
   │  upstream={bankinginsurance|...}, channel={sms|email|...},
   │  tier={high|medium|low}, vendor={twilio|sendgrid|...},
   │  error_code={DSP_VENDOR_002|...}, service={ingestion|...}
   │
   └──────────────────────────────────────────────────────────────
```

### Metrics Catalog

#### Application Metrics (Micrometer → Prometheus)

```java
// ── Counters (monotonically increasing) ──
event.received.total          tags: upstream, service
event.validated.total         tags: upstream, service
event.dispatched.total        tags: upstream, channel, tier, vendor
event.failed.total            tags: upstream, channel, error_code, retryable
event.retry.total             tags: upstream, channel, retry_number
event.dlt.total               tags: upstream, channel
event.dedup.total             tags: upstream, result={hit|miss}
event.expired.total           tags: upstream

// ── Gauges (current value) ──
event.inflight.count          tags: upstream, stage={processing|dispatching|retrying}
circuit.breaker.state         tags: channel, vendor    values: 0=closed, 1=open, 2=half_open

// ── Timers (latency distribution) ──
event.e2e.latency             tags: upstream, channel    (ingest to dispatch, P50/P95/P99)
vendor.api.duration           tags: channel, vendor      (vendor call latency)
db.batch.duration             tags: service, operation={insert|update}
kafka.poll.duration           tags: service, topic

// ── Distribution summaries ──
kafka.consumer.lag            tags: group, topic, partition
kafka.batch.size              tags: service, topic
```

#### Infrastructure Metrics (from OCP + Kafka)

```
kafka_consumergroup_lag_sum           per consumer group + topic
kafka_server_BrokerTopicMetrics_MessagesInPerSec
container_cpu_usage_seconds_total     per pod
container_memory_working_set_bytes    per pod
hikaricp_connections_active           per service
```

### Grafana Dashboard Design

#### Dashboard 1: Executive Overview (for leadership)

```
┌─────────────────────────────────────────────────────────────┐
│  EVENT PROCESSING PLATFORM — REAL-TIME OVERVIEW             │
├─────────────────┬─────────────────┬─────────────────────────┤
│ Events/Second   │ Delivery Rate   │ Active Alerts            │
│ [50,234]        │ [99.7%]         │ [0 critical, 2 warning] │
├─────────────────┴─────────────────┴─────────────────────────┤
│ Throughput by Upstream (last 1 hour)                         │
│ ████████████████████ Banking: 12,500/s                       │
│ ███████████████      OTP Auth: 8,200/s                       │
│ ██████████           Insurance: 6,100/s                      │
│ ████████             Marketing: 5,800/s                      │
│ ...                                                          │
├──────────────────────────────────────────────────────────────┤
│ Delivery Rate by Upstream (last 24h)         │ DLT Rate      │
│ Banking:    99.8%  ████████████████████████   │ 0.02%        │
│ OTP Auth:   99.9%  █████████████████████████  │ 0.01%        │
│ Marketing:  98.5%  ██████████████████████     │ 0.8%         │
│ ...                                          │               │
├──────────────────────────────────────────────────────────────┤
│ Error Distribution (last 1 hour)                             │
│ DSP_VENDOR_002 (5xx):     45  [███]                          │
│ DSP_TIMEOUT_001:          23  [██]                            │
│ VAL_SCHEMA_001:           12  [█]                             │
│ DSP_RATE_001:              8  [█]                             │
└──────────────────────────────────────────────────────────────┘
```

#### Dashboard 2: Pipeline Health (for operations)

```
┌──────────────────────────────────────────────────────────────┐
│ PIPELINE HEALTH — PER STAGE                                  │
├───────────────┬───────────────┬───────────────┬──────────────┤
│  INGESTION    │  PROCESSING   │   DISPATCH    │    RETRY     │
│  Rate: 50K/s  │  Rate: 49.8K  │  Rate: 49.5K  │  Rate: 312  │
│  Lag: 1,200   │  Lag: 800     │  Lag: 2,100   │  Lag: 45    │
│  Errors: 0.1% │  Errors: 0.2% │  Errors: 0.6% │  → DLT: 8  │
├───────────────┴───────────────┴───────────────┴──────────────┤
│ Consumer Lag by Topic (line chart, 1h window)                 │
│ ─── dispatch.sms.high:    [flat at ~200, healthy]            │
│ ─── dispatch.sms.low:     [spike to 8K at 14:30, recovering] │
│ ─── processing.events:    [flat at ~500, healthy]            │
├──────────────────────────────────────────────────────────────┤
│ Circuit Breaker Status                                        │
│ Twilio SMS:   🟢 CLOSED      SendGrid Email: 🟢 CLOSED      │
│ FCM Push:     🟢 CLOSED      Voice vendor:   🟡 HALF_OPEN   │
├──────────────────────────────────────────────────────────────┤
│ Vendor API Latency (P99)    │ DB Batch Insert Duration (P99) │
│ Twilio:      120ms          │ events:         8ms            │
│ SendGrid:    340ms          │ event_dispatch: 12ms           │
│ FCM:          85ms          │ audit_log:      6ms            │
│ Voice:      2,100ms         │                                │
└──────────────────────────────────────────────────────────────┘
```

#### Dashboard 3: Per-Upstream Deep Dive (for SLA review)

```
┌──────────────────────────────────────────────────────────────┐
│ UPSTREAM: BANKING                      [dropdown selector]    │
├──────────────────────────────────────────────────────────────┤
│ SLA Summary (last 24h)                                        │
│ Received: 1,080,000  │ Dispatched: 1,077,840  │ Rate: 99.8% │
│ Failed:       2,160  │ DLT:            216  │ Expired: 0    │
│ Avg Latency: 180ms   │ P99 Latency:   890ms │               │
├──────────────────────────────────────────────────────────────┤
│ Error Breakdown (pie chart)                                   │
│ DSP_VENDOR_002:  45%  │ DSP_TIMEOUT_001: 30%                 │
│ VAL_SCHEMA_001:  15%  │ Other: 10%                           │
├──────────────────────────────────────────────────────────────┤
│ Channel Distribution         │ Priority Distribution          │
│ SMS: 72%  EMAIL: 20%         │ High (1-3): 85%               │
│ OTP: 5%   VOICE: 3%         │ Medium (4-6): 12%              │
│                              │ Low (7-9): 3%                  │
├──────────────────────────────────────────────────────────────┤
│ Hourly Volume (bar chart, 24h)                               │
│ Peak: 14,200/s at 10:15 AM  │ Min: 800/s at 3:00 AM        │
└──────────────────────────────────────────────────────────────┘
```

### Alert Rules (Prometheus Alertmanager)

| Alert | Condition | Severity | Action |
|-------|-----------|----------|--------|
| High consumer lag | `kafka_consumergroup_lag_sum > 10000` for 5 min | Warning | Slack notification |
| Critical consumer lag | `kafka_consumergroup_lag_sum > 50000` for 2 min | Critical | PagerDuty |
| Circuit breaker open | `circuit_breaker_state == 1` for 5 min | Warning | Slack + auto-scale dispatch pods |
| DLT rate spike | `rate(event_dlt_total[5m]) > 10` | Critical | PagerDuty |
| Delivery rate drop | `event_dispatched_total / event_received_total < 0.98` for 10 min | Warning | Slack |
| Vendor latency P99 | `vendor_api_duration_p99 > 5000` for 3 min | Warning | Slack |
| DB batch insert slow | `db_batch_duration_p99 > 500` for 5 min | Warning | Slack + DBA notification |
| Pod OOM risk | `container_memory_working_set_bytes > 0.85 * limit` | Warning | Auto-scale or alert |
| Upstream quota exceeded | `upstream_quota_utilization > 0.95` | Info | Slack (upstream team) |

### Metrics Implementation (Code)

```java
@Component
public class PlatformMetrics {

    private final MeterRegistry registry;

    // Counters
    private Counter receivedCounter(String upstream) {
        return Counter.builder("event.received.total")
            .tag("upstream", upstream)
            .tag("service", "ingestion-service")
            .register(registry);
    }

    private Counter dispatchedCounter(String upstream, String channel, String tier, String vendor) {
        return Counter.builder("event.dispatched.total")
            .tag("upstream", upstream)
            .tag("channel", channel)
            .tag("tier", tier)
            .tag("vendor", vendor)
            .register(registry);
    }

    private Counter failedCounter(String upstream, String channel, String errorCode) {
        return Counter.builder("event.failed.total")
            .tag("upstream", upstream)
            .tag("channel", channel)
            .tag("error_code", errorCode)
            .tag("retryable", String.valueOf(ErrorCode.valueOf(errorCode).isRetryable()))
            .register(registry);
    }

    // Timers
    private Timer e2eLatency(String upstream, String channel) {
        return Timer.builder("event.e2e.latency")
            .tag("upstream", upstream)
            .tag("channel", channel)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    }

    private Timer vendorLatency(String channel, String vendor) {
        return Timer.builder("vendor.api.duration")
            .tag("channel", channel)
            .tag("vendor", vendor)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    }
}
```

### Data Flow: How Metrics Reach Dashboards

```
Application code
    │
    ├─ Micrometer Counter/Timer/Gauge
    │     │
    │     ▼
    │  /actuator/prometheus endpoint (per pod)
    │     │
    │     ▼
    │  Prometheus (scrapes every 15s)
    │     │
    │     ├──▶ Grafana (real-time dashboards, 15s refresh)
    │     └──▶ Alertmanager (threshold-based alerts → PagerDuty/Slack)
    │
    ├─ Structured JSON logs (Logback + MDC)
    │     │
    │     ▼
    │  Fluentd sidecar (per pod)
    │     │
    │     ▼
    │  Splunk (log search, investigation, compliance)
    │
    └─ Audit events (Kafka → audit-service → Oracle)
          │
          ▼
       upstream_metrics_hourly (aggregated by audit-service)
          │
          ▼
       Grafana (reads from Oracle via Prometheus Oracle exporter
                or direct SQL datasource for daily/weekly SLA reports)
```

### What Each Tool Is For

| Tool | What It's Good At | What It's NOT For |
|------|-------------------|-------------------|
| **Prometheus + Grafana** | Real-time metrics, dashboards, alerting on numeric thresholds | Log search, full-text investigation |
| **Splunk** | Log search, investigation, compliance audit, regex-based alerting | Real-time metric aggregation at 50K msg/s |
| **Oracle (upstream_metrics_hourly)** | Historical SLA reporting, daily/weekly/monthly trends, per-upstream billing | Real-time alerting |
| **PagerDuty** | Incident management, escalation, on-call rotation | Dashboards, investigation |

You need ALL of these. They serve different audiences at different timescales:
- **Grafana**: Operations team, real-time (seconds)
- **Splunk**: Engineering team, investigation (minutes to hours)
- **Oracle SLA views**: Leadership, business review (daily/weekly)
- **PagerDuty**: On-call engineer, incidents (immediate)

---

## Impact on Existing Artifacts

### What Changes

| Artifact | Change Needed |
|----------|--------------|
| ARCHITECTURE.md | Update service count from 3 to 5, add priority tier topics |
| architecture-diagram.mermaid | Add dispatch-service and retry-service as separate boxes, add tier labels on dispatch topics |
| workflow-diagram.mermaid | Add priority routing decision point after normalization |
| create-kafka-topics-enterprise.sh | Add tiered dispatch topics (dispatch.sms.high, .medium, .low etc.) |
| application.yml (dispatch) | Split into tier-specific consumer configs with weighted concurrency |
| SDLC Approach doc | Update service count, add observability setup to Phase 2, add dashboard creation to Phase 4 |
| OCP deployment manifests | 5 deployments instead of 3, HPA on dispatch-service |

### What Doesn't Change

| Artifact | Why It's Unchanged |
|----------|-------------------|
| DB schema (V1, V2, V3) | Events table already has priority column. No schema changes. |
| ErrorCode.java | Error codes are stage-based, not service-based. Same enum. |
| platform-common module | Factory interfaces, models, exception hierarchy — all unchanged. |
| UpstreamProcessor interface | Priority is set during normalise() — already supported. |
| ChannelAdapter interface | Adapters don't know about priority — they just dispatch. |
| Outbox pattern | Outbox writes the target topic name. Tiered topics are just different topic names. |
| Batch DB writes | Same JDBC batch pattern, same repositories. |
| Legacy extraction approach | Upstream specs don't change. Priority is a routing concern, not an extraction concern. |
