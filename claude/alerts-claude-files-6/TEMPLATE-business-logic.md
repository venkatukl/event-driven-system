# {UPSTREAM_NAME} — Business Logic Specification

> **Extracted from**: {legacy repo name / module path}
> **Extraction date**: {date}
> **Extracted by**: Copilot @workspace + manual review
> **Reviewed by**: {domain expert name}
> **Review date**: {date}
> **Status**: DRAFT | REVIEWED | APPROVED

---

## Source System

| Property | Value |
|---------|-------|
| Legacy class/module | `{e.g., com.legacy.banking.AlertProcessor}` |
| Input format | {JSON via Kafka / XML via MQ / DB trigger / REST API / ...} |
| Input topic/queue | `{e.g., legacy.banking.alerts}` |
| Peak volume | {X msg/s} |
| Daily average | {Y messages/day} |
| Known peak times | {e.g., 9-10 AM, end-of-day settlement 6-7 PM} |

---

## Validation Rules

| # | Rule Description | Field | Condition | On Failure | Notes |
|---|-----------------|-------|-----------|------------|-------|
| V1 | | | | | |
| V2 | | | | | |
| V3 | | | | | |

**On Failure values**: REJECT (drop, non-retryable), RETRY (transient, retryable), DEFAULT (use fallback value), SKIP (silently ignore), FALLBACK (try alternate channel)

---

## Channel Routing Rules

| # | Condition | Channel(s) | Priority | Notes |
|---|-----------|-----------|----------|-------|
| R1 | | | | |
| R2 | | | | |
| R3 | | | | |
| R_DEFAULT | Default (no other rule matches) | | | |

**Priority**: 1 = highest (OTP, fraud), 5 = normal, 9 = lowest (marketing, bulk)

---

## Template Mapping

| Message Type | Language | Template ID | Channel | Notes |
|-------------|----------|-------------|---------|-------|
| | | | | |

---

## Payload Transformation

| Source Field (legacy) | Target Field (new platform) | Transform | Notes |
|----------------------|---------------------------|-----------|-------|
| | | Direct map / Parse / Mask / Lookup / Compute | |

**Common transforms**: Direct map, Parse to type, Mask (keep last N chars), Lookup (from external system), Compute (derived field), Constant (hardcoded value)

---

## DND / Regulatory Rules

| # | Rule | Applies When | Action | Regulation |
|---|------|-------------|--------|-----------|
| DND1 | | | | |

---

## Time-Window Restrictions

| # | Window | Applies To | Action |
|---|--------|-----------|--------|
| TW1 | | {message types affected} | {QUEUE / SKIP / FALLBACK_CHANNEL} |

---

## Error Handling (Legacy Behavior)

| Scenario | Legacy Behavior | Proposed New Behavior | Notes |
|---------|----------------|----------------------|-------|
| Vendor returns 429 | | | |
| Vendor returns 5xx | | | |
| Vendor timeout | | | |
| Invalid recipient | | | |
| Duplicate message | | | |

---

## Known Edge Cases

| # | Edge Case | Description | How Legacy Handles It | How New Platform Should Handle It |
|---|-----------|-------------|----------------------|----------------------------------|
| E1 | | | | |
| E2 | | | | |

---

## External System Dependencies

| System | Purpose | Lookup Type | Fallback if Unavailable |
|--------|---------|------------|------------------------|
| | {e.g., "Customer DB for name lookup"} | {Sync REST / Async / Cache} | {Use default / Skip field / Reject} |

---

## Metrics / SLA (Current Legacy)

| Metric | Current Value | Target for New Platform |
|--------|--------------|------------------------|
| Delivery rate | {e.g., 98.5%} | {e.g., 99.5%} |
| P99 latency (ingest to dispatch) | {e.g., 800ms} | {e.g., 500ms} |
| Daily failure rate | {e.g., 1.2%} | {e.g., < 0.5%} |

---

## Migration Notes

{Any notes about differences between legacy and new platform behavior. Things that are intentionally changing, things that must stay the same, things that need domain expert sign-off.}

---

## Sign-Off

| Role | Name | Date | Status |
|------|------|------|--------|
| Domain Expert | | | Pending |
| Tech Lead | | | Pending |
| QA Lead | | | Pending |
