# Legacy Business Logic Extraction Strategy

## Why This Is the Highest-Leverage Activity

In a modernization project, the framework code (Kafka consumers, DB repositories, REST clients) is the easy part — Copilot generates it well from architecture docs. The hard part is the business logic: validation rules, routing decisions, edge cases, regulatory constraints, and exception handling that accumulated over years in the legacy system. This logic is rarely documented and lives only in the code.

If you extract this logic into structured Markdown files before starting implementation, you get two things: (1) Copilot generates processors that implement real rules, not placeholders, and (2) you create documentation that survives the modernization — your team will thank you in 2 years.

---

## Step 1: Index the Legacy Codebase in VS Code

### Option A: Legacy is Java (recommended approach)

```
1. Clone the legacy repo (or mount it read-only)
2. Open it as a separate VS Code workspace (NOT in the same workspace as event-platform)
3. Let Copilot index it fully — this happens automatically when you open the workspace
4. Verify indexing: type @workspace in Copilot Chat and ask a question about the codebase
```

### Option B: Legacy is non-Java (COBOL, PL/SQL, .NET, etc.)

Copilot can still read and analyze these. Open the legacy project and use `@workspace` the same way. Copilot handles COBOL, PL/SQL, C#, and most languages.

### Option C: Legacy is a monolith with mixed concerns

If business logic is interleaved with infrastructure code (common in legacy), you'll need to guide Copilot to focus on the right parts. See the prompts below.

---

## Step 2: Extract Business Logic Per Upstream

Open Copilot Chat in the legacy workspace. Use `@workspace` to reference the full codebase. Run these prompts **one upstream at a time**.

### Prompt Template (adapt per upstream):

```
@workspace Analyze the message processing logic for the Banking alerts/notifications 
flow in this codebase. I need you to document EVERY business rule, validation, 
routing decision, and edge case. Structure your output as follows:

## Banking Upstream — Business Logic Specification

### 1. Message Ingestion
- What format do incoming messages arrive in? (JSON, XML, fixed-width, DB trigger?)
- What fields are mandatory vs optional?
- Is there any deduplication logic? What key is used?
- Are there any pre-processing transformations?

### 2. Validation Rules
For each validation, document:
- The rule (e.g., "phone number must be 10 digits starting with 6/7/8/9")
- What happens on failure (reject, retry, default value?)
- Any exceptions or overrides (e.g., "international numbers bypass length check")

### 3. Routing / Channel Selection
- How is the delivery channel (SMS/Email/etc.) decided?
- Are there time-based rules (e.g., "no SMS between 9 PM and 9 AM")?
- Are there amount-based rules (e.g., "transactions > 10L get voice call + SMS")?
- Is there a DND (Do Not Disturb) registry check?
- Are there regulatory rules (e.g., TRAI regulations for promotional messages)?

### 4. Priority & Ordering
- How is priority assigned?
- Are certain message types always high priority (OTP, fraud alert)?
- Is there any ordering guarantee for the same recipient?

### 5. Template Selection
- How is the message template chosen?
- Is it based on message type, language, channel, or some combination?
- Are there A/B testing or template versioning rules?

### 6. Payload Transformation
- What transformations happen between raw input and vendor API payload?
- Are there field mappings, value translations, or enrichments?
- Is data fetched from other systems during processing (e.g., customer name lookup)?

### 7. Error Handling & Edge Cases
- What happens when the vendor API fails?
- Are there fallback channels (e.g., SMS fails → try voice)?
- Are there retry limits specific to this upstream?
- What are the known edge cases that required special handling?

### 8. Regulatory / Compliance
- Any data masking or encryption requirements?
- Audit logging requirements specific to this upstream?
- Retention or deletion rules?

Save the output as a Markdown file.
```

### What `@workspace` Does Here

When you prefix with `@workspace`, Copilot searches across the entire indexed codebase to find relevant code. It will trace through service classes, utility methods, config files, database queries, and even comments to reconstruct the business logic. It's remarkably good at this — often better than a developer reading the code manually, because it can cross-reference across files instantly.

### Important: Verify the Output

Copilot's extraction will be 80–90% accurate. The remaining 10–20% will be:
- Rules it missed because they're in stored procedures or external config files
- Rules it misinterpreted because variable names are cryptic
- Rules that changed since the code was written (dead code paths)

**Have a domain expert review each extracted spec.** Spend 30 minutes per upstream with someone who knows the legacy system. Mark corrections in the document. This is the single highest-ROI activity in your entire modernization.

---

## Step 3: Structure the Output Files

Create one spec file per upstream:

```
event-platform/
└── docs/
    └── legacy-specs/
        ├── BANKING-business-logic.md
        ├── INSURANCE-business-logic.md
        ├── OTP-AUTH-business-logic.md
        ├── MARKETING-business-logic.md
        ├── LOGISTICS-business-logic.md
        └── PAYMENTS-business-logic.md
```

### Spec File Structure (standardized across all upstreams):

```markdown
# {UPSTREAM_NAME} — Business Logic Specification
> Extracted from legacy system: {repo-name}, {date}
> Reviewed by: {domain expert name}, {date}
> Status: DRAFT | REVIEWED | APPROVED

## Source System
- Legacy class/module: `com.legacy.banking.AlertProcessor` (or equivalent)
- Input format: JSON via Kafka / XML via MQ / DB trigger / REST API
- Current volume: ~X messages/second peak, ~Y messages/day average

## Validation Rules
| # | Rule | Field | Condition | On Failure | Notes |
|---|------|-------|-----------|------------|-------|
| V1 | Phone format | recipient | Matches ^[6-9]\d{9}$ | REJECT | India mobile only |
| V2 | Transaction ID | payload.txnId | Non-null, non-empty | REJECT | |
| V3 | Amount range | payload.amount | > 0 and < 10,00,00,000 | REJECT | Max 10 crore |
| V4 | DND check | recipient | Not in DND registry | SKIP or EMAIL fallback | TRAI compliance |

## Channel Routing Rules
| # | Condition | Channel | Priority | Notes |
|---|-----------|---------|----------|-------|
| R1 | OTP message (type=OTP) | OTP | 1 | Always highest priority |
| R2 | Fraud alert (type=FRAUD) | SMS + VOICE | 1 | Dual channel |
| R3 | High value (amount > 1,00,000) | SMS + EMAIL | 2 | Dual channel |
| R4 | Time 9PM-9AM and promotional | QUEUE until 9AM | 7 | TRAI DND hours |
| R5 | Default | SMS | 5 | |

## Template Mapping
| Message Type | Language | Template ID | Channel |
|-------------|----------|-------------|---------|
| TXN_DEBIT | EN | BANK_DEBIT_EN_V3 | SMS |
| TXN_DEBIT | HI | BANK_DEBIT_HI_V2 | SMS |
| TXN_CREDIT | EN | BANK_CREDIT_EN_V3 | SMS |
| OTP | EN | BANK_OTP_EN_V1 | OTP |
| FRAUD_ALERT | EN | BANK_FRAUD_EN_V1 | SMS + VOICE |

## Payload Transformation
| Source Field (legacy) | Target Field (new) | Transform |
|----------------------|-------------------|-----------|
| txn_id | transactionId | Direct map |
| amt | amount | Parse to BigDecimal, 2 decimal places |
| acct_no | accountMasked | Mask: keep last 4 digits |
| cust_name | — | Looked up from customer DB, injected into template |

## Error Handling (legacy-specific)
| Scenario | Legacy Behavior | New Platform Behavior |
|---------|----------------|----------------------|
| Vendor returns 429 | Retry after 60s, max 5 | Retry with exponential backoff, max from config |
| SMS fails for DND number | Log and skip | Fallback to email if email on file |
| Duplicate txn_id within 1 hour | Silently drop | Dedup via Redis + DB (24h window) |

## Known Edge Cases
1. **Batch settlement messages**: End-of-day batch generates ~50K messages in 2 minutes. 
   Need burst handling.
2. **International numbers**: +1, +44 prefix — different SMS vendor (not Twilio India).
3. **Zero-amount transactions**: ATM balance inquiry — should NOT trigger alert.
4. **Scheduled maintenance window**: Banking upstream sends a "MAINT_START" control message 
   that should pause processing for that upstream only.
```

---

## Step 4: Feed Specs to Copilot During Implementation

### Updated Plan Mode Prompt (from COPILOT-DEV-GUIDE.md)

When you reach Phase 1A (feed architecture to Copilot), add the legacy specs:

```
I need you to understand the architecture of this event-processing platform before we 
start coding. Please read these files and confirm your understanding:

Architecture:
- docs/ARCHITECTURE.md
- docs/ARCHITECTURE-ADDENDUM.md  
- docs/GAP-ANALYSIS.md
- docs/workflow-diagram.mermaid
- common/src/main/java/com/platform/common/error/ErrorCode.java
- schema/V1__init_schema.sql

Legacy Business Logic (one per upstream):
- docs/legacy-specs/BANKING-business-logic.md
- docs/legacy-specs/INSURANCE-business-logic.md
- docs/legacy-specs/OTP-AUTH-business-logic.md
- docs/legacy-specs/MARKETING-business-logic.md
- docs/legacy-specs/LOGISTICS-business-logic.md
```

### Updated Phase C Prompt (implementing upstream processors):

```
Working on Phase C from docs/PLAN.md.

Implement the BankingUpstreamProcessor using the EXACT business rules documented in
docs/legacy-specs/BANKING-business-logic.md.

Specifically:
1. Implement ALL validation rules (V1 through V4 in the spec)
2. Implement the channel routing logic (R1 through R5)
3. Implement the template mapping table
4. Implement the payload transformation (field mapping + masking)
5. Handle the documented edge cases (batch settlement, international numbers, 
   zero-amount, maintenance window)

Use the ErrorCode enum for all error cases. Use the existing UpstreamProcessor interface.
The spec is the source of truth — implement every rule listed, not a subset.
```

This is the difference between Copilot generating:
```java
// TODO: implement banking validation rules
```
and:
```java
if (!recipient.matches("^[6-9]\\d{9}$")) {
    throw new NonRetryableException(ErrorCode.VAL_SCHEMA_002, corrId, 
        "Banking requires Indian mobile number (starts with 6-9, 10 digits)");
}
```

---

## Step 5: Cross-Reference Gaps

After extracting all upstream specs, create a cross-reference matrix:

```markdown
## Cross-Upstream Comparison

| Capability | Banking | Insurance | OTP | Marketing | Logistics |
|-----------|---------|-----------|-----|-----------|-----------|
| DND check required | Yes | Yes | No (exempt) | Yes | No |
| Time-window restriction | Yes (9PM-9AM) | No | No | Yes (9PM-9AM) | No |
| Multi-channel delivery | Yes (fraud) | No | No | No | Yes (webhook+email) |
| Template versioning | V3 | V1 | V1 | V2 | V1 |
| Fallback channel | SMS→Email | None | None | Email→Push | Webhook→Email |
| Regulatory constraints | TRAI, RBI | IRDAI | None | TRAI | None |
| Burst handling needed | Yes (EOD batch) | No | Yes (login spikes) | Yes (campaigns) | No |
```

This matrix reveals:
- Shared rules that should be in platform-common (DND check, time-window)
- Upstream-specific rules that belong in individual processors
- Which upstreams are simple enough for ConfigurableUpstreamProcessor
- Which upstreams genuinely need custom Java implementations

---

## Estimated Time for Legacy Extraction

| Activity | Time per Upstream | Total (5 upstreams) |
|---------|-------------------|---------------------|
| Copilot @workspace extraction | 30 min | 2.5 hours |
| Domain expert review + corrections | 30 min | 2.5 hours |
| Standardize format, cross-reference | — | 1 hour |
| **Total** | — | **~6 hours** |

This is 6 hours that saves you 2–3 weeks of manually porting business rules during implementation. It's the single highest-ROI preparation step in the entire modernization.
