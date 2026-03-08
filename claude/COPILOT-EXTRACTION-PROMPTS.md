# Legacy Business Logic Extraction — Copilot Prompt Kit

## How This Works

You'll run these prompts in **GitHub Copilot Agent mode** with `@workspace` in the legacy codebase workspace. The prompts are designed as a 4-stage funnel:

```
Stage 1: DISCOVERY     → Find where the logic lives (entry points, flow)
Stage 2: DEEP EXTRACT  → Pull every rule, condition, and edge case per upstream
Stage 3: STRUCTURED    → Convert into the standardized template format
Stage 4: VERIFY        → Cross-check for completeness and dead code
```

Why 4 stages instead of 1 big prompt? Because Copilot Agent works best when each prompt has a focused goal. A single "extract everything" prompt produces shallow results — it skims the codebase and gives you summaries. The staged approach forces it to go deep on each aspect.

**Important**: Run these in **Agent mode**, not Ask/Edit mode. Agent mode can create files, search across the workspace, and chain reasoning across multiple files. Ask mode just answers questions.

---

## BEFORE YOU START

### Workspace Setup

```
1. Open ONLY the legacy codebase as a VS Code workspace
2. Do NOT have the new event-platform project open simultaneously
   (Copilot will confuse the two codebases)
3. Wait for indexing to complete (check the Copilot status icon in the bottom bar)
4. Create an empty output folder: mkdir -p docs/extracted-specs
```

### Verify Indexing Works

Run this quick test first:

```
@workspace What are the main entry points for processing incoming messages 
or notifications in this codebase? List the top-level classes or methods 
that handle inbound message flow.
```

If Copilot returns relevant class names, indexing is working. If it says "I couldn't find relevant code," the workspace isn't indexed properly — close and reopen VS Code.

---

## STAGE 1: DISCOVERY (Run Once for Entire Codebase)

### Prompt 1A — Map the Processing Pipeline

```
@workspace I need to understand the complete message processing pipeline in this codebase.
Trace the flow from the moment a message/notification/alert enters the system to the 
moment it is sent to the end user (via SMS, email, push notification, voice call, 
webhook, or any other channel).

For each step in the pipeline, tell me:
1. The class and method name
2. What it does (one sentence)
3. What it passes to the next step
4. What can go wrong at this step (exceptions, error paths)

Format as a numbered pipeline:
Step 1: [ClassName.methodName] — Description → passes X to Step 2
Step 2: [ClassName.methodName] — Description → passes Y to Step 3
...

If there are multiple pipelines (e.g., different paths for different message types 
or different upstream sources), map each one separately and label them.

Also identify:
- Where configuration is loaded from (properties files, DB tables, constants)
- Where external systems are called (vendor APIs, customer DB lookups, DND registries)
- Where retry/error handling logic lives
```

### Prompt 1B — Identify All Upstream Sources

```
@workspace Based on the processing pipeline you identified, list every distinct 
upstream source or message type that enters this system.

For each upstream, tell me:
- The identifier used in code (enum value, string constant, topic name, queue name)
- The entry point class/method specific to this upstream
- The message format (JSON, XML, key-value, fixed-width, etc.)
- Approximate code path — which classes does this upstream's message flow through 
  that are DIFFERENT from other upstreams?
- Any upstream-specific configuration (properties, DB config, constants)

Present as a table:
| # | Upstream Name | Identifier | Entry Point | Format | Unique Classes | Config Source |
```

### Prompt 1C — Map Shared vs Upstream-Specific Logic

```
@workspace Looking at the processing pipeline and the upstream sources you identified,
separate the logic into two categories:

SHARED LOGIC (same for all upstreams):
- List every class/method that ALL message types flow through regardless of upstream
- Note what each shared component does

UPSTREAM-SPECIFIC LOGIC (varies per upstream):
- List every class/method that behaves differently based on the upstream or message type
- For each, note HOW it varies (different validation rules? different routing? 
  different templates?)

This distinction is critical — shared logic will go into our platform-common module,
upstream-specific logic will go into individual UpstreamProcessor implementations.
```

**After Stage 1**: You should have a clear map of the pipeline, all upstreams, and what's shared vs specific. Save this output — you'll reference it in later stages.

---

## STAGE 2: DEEP EXTRACT (Run Once Per Upstream)

Replace `{UPSTREAM}` with the actual upstream name (e.g., "Banking", "Insurance").
Replace `{EntryClass}` with the class name identified in Stage 1.

### Prompt 2A — Validation Rules

```
@workspace Now focusing ONLY on the {UPSTREAM} upstream message processing path.

Starting from {EntryClass}, trace through EVERY validation check, guard clause, 
if-condition, regex check, null check, range check, format check, and business rule 
that a {UPSTREAM} message must pass before it is accepted for processing.

For EACH validation, document:
1. **Rule ID**: V1, V2, V3, etc.
2. **Field being validated**: The exact field name from the message
3. **Condition**: The exact condition checked (include regex patterns, ranges, enum values)
4. **Code location**: ClassName.methodName, line number if possible
5. **On failure**: What happens? (exception thrown? default value used? message skipped? 
   logged and continued? different error code?)
6. **Is it blocking?**: Does failure stop processing, or does it continue checking other rules?

IMPORTANT: Do not summarize. I need EVERY rule, even trivial null checks.
Look inside utility methods, validators, helper classes — rules are often hidden 
in shared utility methods.

Also check for:
- Rules in annotations (e.g., @NotNull, @Pattern, @Size, custom annotations)
- Rules in external configuration (properties files, DB tables, XML config)
- Rules that are conditionally applied (e.g., "only validate phone format if channel is SMS")

Format as a table:
| Rule ID | Field | Condition | Code Location | On Failure | Blocking? | Notes |
```

### Prompt 2B — Channel Routing & Priority Logic

```
@workspace Still focusing on the {UPSTREAM} processing path.

Find ALL logic that determines:

1. **Which delivery channel** (SMS, Email, OTP, Voice, Push, Webhook) is used:
   - Where in the code is this decision made?
   - What conditions drive the channel selection?
   - Is there a priority order if multiple channels apply?
   - Are there fallback channels if the primary fails?

2. **Message priority** assignment:
   - How is priority determined?
   - Is it hardcoded, config-driven, or computed from message content?
   - Are there priority overrides for specific scenarios?

3. **Time-based routing**:
   - Are there rules about WHEN messages can be sent (time of day, day of week)?
   - What happens to messages received outside the allowed window?
   - Are there timezone considerations?

4. **DND (Do Not Disturb) / opt-out handling**:
   - Is there a DND check? Where is the registry looked up?
   - What happens when recipient is on DND? (skip? email instead? queue?)
   - Are certain message types exempt from DND (e.g., OTP, fraud alerts)?

5. **Regulatory rules**:
   - Any TRAI, RBI, IRDAI, or other regulatory logic?
   - Any geographic routing rules (different vendor for international numbers)?

For each rule, provide:
| Rule ID | Condition | Result (Channel/Priority) | Code Location | Notes |
```

### Prompt 2C — Template Selection & Payload Transformation

```
@workspace Still focusing on the {UPSTREAM} processing path.

Document:

1. **Template selection logic**:
   - How is the message template chosen?
   - What factors determine which template? (message type, language, channel, 
     customer segment, A/B test, time of day?)
   - Where are templates stored? (DB, properties, hardcoded strings, external service?)
   - Is there template versioning? How is the version resolved?
   - What happens if the template is not found?

2. **Payload transformation** — field by field mapping:
   - For every field in the outbound message (what gets sent to the vendor API),
     trace back where the value comes from
   - Include: direct field mapping, computed fields, enriched fields (looked up from 
     other systems), masked fields, formatted fields (date formatting, currency formatting)
   
   Format:
   | Outbound Field | Source | Transform | Code Location |
   
   Where Transform is one of: DIRECT_MAP, FORMAT (specify how), MASK (specify what's 
   kept), LOOKUP (specify where), COMPUTE (specify formula), CONSTANT (specify value),
   CONDITIONAL (specify condition)

3. **External lookups during processing**:
   - Does processing call any external system? (customer DB, template service, 
     config service, DND registry, URL shortener)
   - For each: what's looked up, is it cached, what's the fallback if it's unavailable?
```

### Prompt 2D — Error Handling, Retries & Edge Cases

```
@workspace Still focusing on the {UPSTREAM} processing path.

Document EVERY error handling path:

1. **Exception handling**:
   - What exceptions can be thrown during {UPSTREAM} processing?
   - For each: where is it caught? what happens? (retry? skip? alert? fallback?)
   - Are there catch-all handlers? What do they do?

2. **Retry behavior**:
   - What conditions trigger a retry?
   - How many retries? What's the delay between retries?
   - Is there backoff (linear, exponential)?
   - What happens after retries are exhausted?
   - Is there a dead letter queue or table for failed messages?

3. **Vendor-specific error handling**:
   - What HTTP status codes are handled explicitly?
   - Is there different handling for timeout vs 4xx vs 5xx?
   - Does the code check for vendor-specific error codes in the response body?
   - Is there vendor failover (try vendor B if vendor A fails)?

4. **Edge cases and special handling**:
   Search for TODO comments, FIXME, HACK, WORKAROUND, "special case", "edge case",
   and any conditional logic that handles unusual scenarios.
   
   Also look for:
   - Batch processing logic (end-of-day, bulk sends)
   - Control messages (maintenance mode, pause/resume)
   - Rate limiting (self-imposed limits on vendor calls)
   - Duplicate detection logic
   - Message expiry logic (TTL, scheduled messages)

   For each edge case:
   | # | Description | Trigger Condition | Handling | Code Location | Is This Still Needed? |
```

---

## STAGE 3: STRUCTURE INTO TEMPLATE (Run Once Per Upstream)

After running all Stage 2 prompts for an upstream, run this to consolidate:

### Prompt 3 — Compile into Standardized Spec

```
@workspace Using all the information you've extracted about {UPSTREAM} processing 
in our previous conversation, compile a complete business logic specification 
in this EXACT format. Save it as docs/extracted-specs/{UPSTREAM}-business-logic.md

Use this structure:

# {UPSTREAM} — Business Logic Specification
> Extracted from: {repo name}
> Extraction date: {today's date}
> Status: DRAFT — Needs domain expert review

## Source System
| Property | Value |
|----------|-------|
| Legacy class/module | {main class path} |
| Input format | {format} |
| Input topic/queue | {topic or queue name} |
| Peak volume | {if mentioned anywhere in code/config/comments} |

## Validation Rules
{Use the table from Prompt 2A — include ALL rules}

## Channel Routing Rules
{Use the table from Prompt 2B — include ALL rules}

## Template Mapping
{Use the table from Prompt 2C section 1}

## Payload Transformation
{Use the table from Prompt 2C section 2}

## External Dependencies
{Use the info from Prompt 2C section 3}

## DND / Regulatory Rules
{Use the info from Prompt 2B sections 4 and 5}

## Time-Window Restrictions
{Use the info from Prompt 2B section 3}

## Error Handling
{Use the table from Prompt 2D section 1-3}

## Known Edge Cases
{Use the table from Prompt 2D section 4}

## Migration Notes
- Rules that should be PRESERVED exactly as-is in the new system: {list}
- Rules that should be CHANGED or IMPROVED: {list with reasoning}
- Rules that appear to be DEAD CODE or no longer relevant: {list}

IMPORTANT: Do not omit any rules. Every validation, routing rule, template mapping,
and edge case from our previous analysis must appear in this document.
If in doubt about whether something is still relevant, include it and mark it 
as "NEEDS VERIFICATION" in the Notes column.
```

---

## STAGE 4: VERIFY (Run Once Per Upstream)

### Prompt 4A — Completeness Check

```
@workspace I have extracted business logic for {UPSTREAM} into 
docs/extracted-specs/{UPSTREAM}-business-logic.md

Please cross-check this spec against the actual codebase:

1. Read the spec file I created
2. Trace through the {UPSTREAM} processing code path again
3. Identify any rules, validations, routing logic, error handling, or edge cases 
   that exist in the CODE but are MISSING from the spec
4. Identify any rules in the SPEC that you cannot find corresponding code for 
   (possible documentation errors)

Format your findings as:

### Rules in Code but Missing from Spec
| Rule Description | Code Location | Why It Might Have Been Missed |

### Rules in Spec but Not Found in Code
| Rule from Spec | Possible Explanation |

### Rules That Need Clarification
| Rule | What's Unclear | What a Domain Expert Should Clarify |
```

### Prompt 4B — Dead Code & Technical Debt Identification

```
@workspace In the {UPSTREAM} processing path, identify:

1. **Dead code**: Methods or branches that are never called (unreachable code, 
   commented-out blocks, feature flags that are permanently off)
2. **Deprecated patterns**: Code that uses outdated libraries, deprecated APIs, 
   or patterns that should not be carried to the new system
3. **Hardcoded values**: Magic numbers, hardcoded URLs, hardcoded credentials, 
   hardcoded timeouts that should become configuration
4. **Performance concerns**: N+1 queries, synchronous calls that should be async, 
   missing connection pooling, missing caching
5. **Known bugs or workarounds**: Code comments mentioning bugs, temporary fixes, 
   or workarounds that were never properly resolved

For each finding, recommend whether to:
- CARRY FORWARD (replicate in new system)
- FIX DURING MIGRATION (implement properly in new system)
- DROP (do not migrate, it's dead/irrelevant)

This becomes our migration debt register.
```

---

## AFTER ALL UPSTREAMS ARE EXTRACTED

### Prompt 5 — Cross-Upstream Analysis

Run this once after extracting all upstreams:

```
Read all spec files in docs/extracted-specs/ and create a cross-reference analysis.
Save it as docs/extracted-specs/CROSS-REFERENCE.md

Include:

## 1. Shared Rules (candidates for platform-common)
Rules that appear in 3+ upstreams with identical or near-identical logic.
These should be extracted into shared platform services, not duplicated 
in each UpstreamProcessor.

| Rule | Upstreams Using It | Variations |

## 2. Channel Routing Comparison
| Upstream | Default Channel | Conditional Channels | Fallback | DND Handling |

## 3. Validation Overlap
Which validations are the same across upstreams (e.g., phone format) vs unique?

## 4. ConfigurableProcessor Candidates
Based on the complexity of each upstream's rules, which ones could use the 
ConfigurableUpstreamProcessor (no-code, config-driven) vs which need custom Java?

| Upstream | Rule Count | Complexity | External Lookups | Recommendation |

Complexity: SIMPLE (< 5 rules, no lookups, no conditional routing)
            MEDIUM (5-15 rules, simple lookups, standard routing)
            COMPLEX (15+ rules, multiple lookups, conditional multi-channel, edge cases)

SIMPLE → ConfigurableUpstreamProcessor (zero code)
MEDIUM → ConfigurableUpstreamProcessor with custom validator hook
COMPLEX → Custom UpstreamProcessor implementation

## 5. Migration Risk Assessment
| Upstream | Risk Level | Reason | Mitigation |

Risk is HIGH when: many edge cases, external dependencies, regulatory rules,
high volume, complex template logic.
Risk is LOW when: simple validation, single channel, no external lookups.
```

---

## Quick Reference: Which Mode to Use

| Task | Mode | Why |
|------|------|-----|
| Stage 1 (Discovery) | **Agent** with @workspace | Needs to search across entire codebase |
| Stage 2 (Deep Extract) | **Agent** with @workspace | Needs to trace through specific code paths |
| Stage 3 (Structure) | **Agent** with @workspace | Needs to create/write the spec file |
| Stage 4 (Verify) | **Agent** with @workspace | Needs to cross-reference spec against code |
| Stage 5 (Cross-ref) | **Agent** (can reference files) | Reads multiple spec files and compares |

Always use **Agent mode** for this workflow. Ask mode cannot create files or do multi-step reasoning across the codebase.

---

## Time Estimate

| Stage | Per Upstream | One-time |
|-------|-------------|----------|
| Stage 1: Discovery | — | 30-45 min |
| Stage 2: Deep Extract | 45-60 min | — |
| Stage 3: Structure | 15-20 min | — |
| Stage 4: Verify | 15-20 min | — |
| Stage 5: Cross-reference | — | 30-45 min |
| **Domain expert review** | **30 min** | — |
| **Total for 5 upstreams** | — | **~8 hours** |

---

## What You'll Have at the End

```
docs/extracted-specs/
├── BANKING-business-logic.md         ← Complete spec, reviewed
├── INSURANCE-business-logic.md       ← Complete spec, reviewed
├── OTP-AUTH-business-logic.md        ← Complete spec, reviewed
├── MARKETING-business-logic.md       ← Complete spec, reviewed
├── LOGISTICS-business-logic.md       ← Complete spec, reviewed
├── CROSS-REFERENCE.md                ← Shared rules, overlap, risk
└── MIGRATION-DEBT-REGISTER.md        ← Dead code, tech debt, fixes
```

These files become first-class inputs to the new system's Plan phase in Copilot,
feeding directly into UpstreamProcessor implementations with real business rules
instead of TODO placeholders.
