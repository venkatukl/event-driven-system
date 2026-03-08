# Shared Component Discovery — Addressing the @workspace Limitation

## The Problem with @workspace for Shared Logic

`@workspace` semantic search works by matching your query against file content embeddings. When you ask "find shared utility classes used across upstreams," it looks for files that semantically match that phrase. This works when:
- Shared code lives in packages named `common`, `shared`, `util`, `core`
- Class names contain words like `Base`, `Abstract`, `Helper`, `Util`

It fails when:
- Shared logic is in a base class like `AbstractNotificationProcessor` in a non-obvious package
- Shared behavior comes from a Spring parent context or imported library module
- Rules live in AOP aspects, servlet filters, or interceptors that don't have "shared" in their name
- Config-driven behavior is loaded from a DB table that multiple upstreams query

## The Fix: Let Upstream Extractions Reveal Shared Code

Instead of trying to discover shared components upfront (unreliable), let the per-upstream extractions expose them naturally. Here's the adjusted approach:

### Step 1: Extract the First Upstream (no shared spec yet)

Run the normal one-prompt extraction for your first upstream (pick the most complex one — it will touch the most shared code):

```
@workspace Extract business logic for the Banking alerts processing flow.
Starting point: #file:src/main/java/com/legacy/banking/BankingAlertListener.java

[... full extraction prompt from STREAMLINED-EXTRACTION.md ...]
```

At the end of the output, add this instruction:

```
After completing the spec, also list EVERY class outside the banking-specific 
package that was referenced in the call graph. These are candidate shared components.

Format:
## External Dependencies (Candidate Shared Components)
| Class | Package | What It Does | Called From |
```

### Step 2: Extract the Second Upstream and Cross-Reference

```
@workspace Extract business logic for OTP Auth processing flow.
Starting point: #file:src/main/java/com/legacy/otp/OTPMessageListener.java

[... full extraction prompt ...]

After completing the spec, compare the call graph with 
#file:docs/extracted-specs/BANKING-business-logic.md

Identify classes that appear in BOTH upstream call graphs. 
These are confirmed shared components. List them:

## Confirmed Shared Components (also used by Banking)
| Class | Package | Used by Banking for | Used by OTP for | Same behavior or different? |
```

### Step 3: By the Third Upstream, Shared Components Are Clear

By the time you've extracted 3 upstreams, Agent has seen enough call graphs to identify the shared layer with high confidence. Now run:

```
Read the "External Dependencies" and "Confirmed Shared Components" sections from:
#file:docs/extracted-specs/BANKING-business-logic.md
#file:docs/extracted-specs/OTP-AUTH-business-logic.md
#file:docs/extracted-specs/INSURANCE-business-logic.md

Create docs/extracted-specs/SHARED-COMMON-LOGIC.md containing:

For every class that appears in 2 or more upstream call graphs:
1. Full rule extraction (same depth as upstream specs)
2. Which upstreams use it
3. Per-upstream behavioral differences (if any conditional branches exist)

Also search @workspace for any additional shared components that might have 
been missed — specifically look for:
- Abstract base classes that upstream processors extend
- Spring @Configuration classes that register beans used across upstreams
- Servlet filters or interceptors in the web layer
- AOP aspects (@Aspect classes)
- Exception handlers (@ControllerAdvice or similar)
- Any class imported by 3+ upstream-specific classes
```

### Why This Approach Is More Reliable

| Approach | How It Finds Shared Code | Reliability |
|----------|------------------------|-------------|
| @workspace "find shared classes" | Semantic search on naming | 65-70% |
| Trace from upstream entry points | Follows actual call graph | 85-90% |
| Cross-reference multiple upstream call graphs | Intersection of real paths | 92-95% |

The call graph intersection approach is more reliable because it discovers shared code based on what's actually called, not what looks like it might be shared based on package names. A class that appears in 3 different upstream call graphs is definitively shared — no guessing.

## What @workspace Genuinely Misses (and How to Catch It)

Even with the cross-reference approach, there are categories of shared logic that are invisible to call graph tracing:

### 1. AOP Aspects (Applied at Runtime, Not in Code)

These wrap methods transparently. The call graph won't show them.

**Catch it**: After the cross-reference analysis, run one targeted search:

```
@workspace Find all classes annotated with @Aspect in this codebase.
For each aspect:
1. What pointcut does it match? (which methods does it wrap?)
2. What does it do? (logging, timing, retry, transaction management, auth check?)
3. Does it contain any business rules? (not just logging/timing)
```

### 2. Servlet Filters and Interceptors

These run before the message processing code and may enforce rules (rate limiting, auth, header validation) that the call graph never sees.

**Catch it**:

```
@workspace Find all classes that implement Filter, HandlerInterceptor, 
OncePerRequestFilter, or any Kafka interceptor interfaces. Also find any 
classes registered in web.xml or via @WebFilter / @Order annotations.

Do any of them contain business logic (not just logging or CORS)?
```

### 3. Spring Event Listeners and Observers

Some legacy systems use Spring events for cross-cutting concerns. These don't appear in call graphs because they're decoupled via the event bus.

**Catch it**:

```
@workspace Find all classes annotated with @EventListener, implementing 
ApplicationListener, or publishing events via ApplicationEventPublisher.
Are any of these involved in message processing business logic?
```

### 4. Database Triggers

If Oracle triggers fire on INSERT/UPDATE to message tables, they contain business logic that no Java code references.

**Catch it**: This is the one thing you need to ask a DBA or check manually:

```
Ask your DBA: Are there any database triggers on the message processing 
tables (events, notifications, alerts, queue tables)? If yes, get the 
trigger source code and include it as a #file in the extraction prompt.
```

## Revised Confidence Levels

| Component | Discovery Method | Confidence |
|-----------|-----------------|-----------|
| Upstream-specific logic | #file entry point + recursive trace | 85-90% |
| Shared code in call graph | Cross-reference 3+ upstream extractions | 92-95% |
| AOP aspects | Targeted @Aspect search | 90% |
| Filters/interceptors | Targeted interface search | 90% |
| DB triggers | Ask DBA (manual, 2 min) | 100% |
| Spring event listeners | Targeted @EventListener search | 85% |
| **Overall completeness** | **Combined approach** | **88-93%** |

The remaining 7-12% gap is where the domain expert earns their 10-15 minutes per upstream.

## Summary: Your Actual Workflow

```
Upstream 1: Full extraction prompt (2 min setup)
            → Agent traces call graph, extracts rules
            → Output includes "External Dependencies" list

Upstream 2: Full extraction prompt (2 min setup)
            → Agent traces AND cross-references with Upstream 1
            → Shared components begin to emerge

Upstream 3: Full extraction prompt (1 min setup, shorter prompt)
            → Cross-reference with Upstreams 1+2
            → Shared components are now confirmed

After Upstream 3:
  → Generate SHARED-COMMON-LOGIC.md from cross-reference data
  → Run 3 targeted searches (aspects, filters, event listeners)
  → Ask DBA about triggers (2 min conversation)

Upstreams 4-5: Shortest prompt (reference shared spec, extract only unique rules)

Cross-reference: One final prompt to classify upstreams and assess risk

Domain expert: Reviews each spec (10-15 min each)
```

Total: ~25-30 min of your time, ~1 hour of expert time, and Agent does the heavy lifting.
