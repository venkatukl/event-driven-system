# Streamlined Legacy Extraction — Minimal Manual Effort

## What You Actually Need to Do Manually

Per upstream, your total manual effort is one prompt setup that takes about 2 minutes:

1. **Name the entry point file** (you already know this)
2. **Mention any hidden files** that aren't reachable from Java code (stored procedures, external config files, CSV rule tables — only if they exist)
3. **Paste the prompt below**
4. **Review the output** (or hand it to domain expert)

Everything else — call graph tracing, method explanation, config resolution, rule extraction, structuring — Agent mode handles.

---

## The One Prompt Per Upstream

Open Copilot Chat in **Agent mode** in your legacy workspace.

### For an upstream with a known entry point:

```
@workspace I need you to extract a complete business logic specification for 
the OTP message processing flow.

Starting point: #file:src/main/java/com/legacy/otp/OTPMessageListener.java

DO THIS AUTOMATICALLY:
1. Start from the onMessage/process/handle method in that file
2. Trace EVERY method call recursively — follow each call to its definition, 
   including into shared/common/utility classes, even across modules
3. For each class you encounter, also check:
   - @Value and @ConfigurationProperties annotations for config values
   - Any .properties or .yml files referenced
   - Any SQL queries or repository/DAO calls (find the actual query)
   - Any annotations that enforce validation (@NotNull, @Pattern, @Valid, custom)
   - Any AOP aspects or interceptors that wrap the method
4. When you hit conditional logic (if/else, switch, ternary), document EACH branch 
   as a separate business rule with the actual condition and outcome
5. When you encounter a config value, find its current value in properties/yml 
   files and state the concrete value, not just the variable name
6. When you find error handling (try/catch, error codes, exception mapping), 
   document what each error path does

PRODUCE THIS OUTPUT saved as docs/extracted-specs/OTP-AUTH-business-logic.md:

# OTP Auth — Business Logic Specification
> Auto-extracted from codebase, needs domain expert review

## Call Graph
Show the complete method call chain as a tree:
  OTPMessageListener.onMessage()
    → OTPService.process()
      → Validator.validate()
        → RecipientValidator.checkPhone()   [SHARED]
        → ...
      → ChannelRouter.resolve()
      → TemplateService.getTemplate()
      → VendorAdapter.send()
        → RetryHandler.onFailure()          [SHARED]

## Validation Rules
| # | Rule | Field | Condition (with actual values) | On Failure | Code Location | Confidence |
Mark confidence as HIGH (clear code), MEDIUM (inferred from context), 
or LOW (uncertain, needs verification).

## Channel Routing Rules  
| # | Condition (concrete) | Channel | Priority | Code Location | Confidence |

## Template Mapping
| Message Type | Template ID/Name | Selection Logic | Code Location |

## Payload Transformation
| Source Field | Target Field | Transform | Code Location |

## Config Values That Drive Rules
| Property Key | Current Value | Where Used | What It Controls |

## Error Handling
| Scenario | Behavior | Retry? | Code Location |

## Edge Cases & Special Logic
| # | Description | Trigger | Handling | Code Location | Confidence |

## External Dependencies
| System | Purpose | Sync/Async | Fallback |

## Items Needing Verification
List anything you could not confirm from code — missing references, 
ambiguous logic, config values you could not locate.
```

### If there are hidden files Copilot can't reach by tracing:

Add this to the same prompt, before the "DO THIS AUTOMATICALLY" section:

```
Additional files involved in this flow that may not be directly traceable 
from the Java call graph:
#file:sql/procedures/sp_check_dnd_registry.sql
#file:src/main/resources/otp-routing-rules.csv
#file:config/external/vendor-timeout-config.xml

Include rules from these files in your extraction.
```

That's it. One prompt. Two minutes of setup.

---

## For Shared/Common Logic (Run Once Before Upstreams)

Before extracting individual upstreams, extract the shared layer:

```
@workspace Identify all SHARED utility/common classes in this codebase that are 
used by MULTIPLE message processing flows (not specific to one upstream/message type).

Look for packages or directories named common, shared, util, core, framework, base.
Also look for classes that are injected into multiple services.

For each shared component you find:
1. What it does
2. Which upstream flows use it
3. Every rule or behavior it enforces
4. Any per-upstream conditional branches (if it behaves differently for 
   different message types, document each branch)

Save as docs/extracted-specs/SHARED-COMMON-LOGIC.md

Then when I extract individual upstreams, I'll reference this file so we 
don't duplicate these rules.
```

---

## For Subsequent Upstreams (Even Simpler)

After the first upstream, the shared logic is already extracted. The prompt gets shorter:

```
@workspace Extract business logic for the Banking alerts processing flow, 
same format as #file:docs/extracted-specs/OTP-AUTH-business-logic.md

Starting point: #file:src/main/java/com/legacy/banking/BankingAlertListener.java

Shared rules are already documented in 
#file:docs/extracted-specs/SHARED-COMMON-LOGIC.md — don't re-extract those. 
Just note which shared rules apply and document any Banking-specific 
overrides or additions.

Save as docs/extracted-specs/BANKING-business-logic.md
```

One prompt. Under a minute of setup. Copilot already knows the output format from the first extraction.

---

## Cross-Reference (Run Once After All Upstreams)

```
Read all files in docs/extracted-specs/ and create docs/extracted-specs/CROSS-REFERENCE.md:

1. Which upstreams are SIMPLE enough for config-driven processing (< 5 unique rules, 
   no external lookups, single channel)?
2. Which need custom code (complex routing, multi-channel, external dependencies)?
3. What shared rules should move to the platform-common module?
4. What's the migration risk per upstream (based on complexity, edge cases, 
   external dependencies)?

Format as tables.
```

---

## Total Effort

| Activity | Time | Who |
|----------|------|-----|
| Shared logic extraction | 5 min (one prompt, wait for output) | You |
| Per upstream extraction | 2 min setup + 3-5 min Copilot runs | You |
| Cross-reference | 2 min (one prompt) | You |
| Domain expert review | 10-15 min per upstream | Expert |
| **Total for 5 upstreams** | **~30 min you + ~1 hour expert** | |

Compare this to the previous guide's estimate of 8 hours. The difference is letting Agent mode do what it's designed to do instead of manually pre-chewing its work.

---

## When the Simple Approach Isn't Enough

In roughly 20% of cases, the one-prompt approach will produce incomplete results for a specific upstream. Signals:

- The "Items Needing Verification" section has more than 5 entries
- The Confidence column shows multiple LOWs
- The Call Graph looks suspiciously short (missed a major branch)

When this happens, run ONE targeted follow-up:

```
@workspace The extraction for {UPSTREAM} seems incomplete. Specifically, 
I notice {the gap — e.g., "no DND check logic was found" or "the retry 
behavior section is empty" or "the call graph doesn't show any DB queries"}.

Search more broadly for:
- Classes containing "{relevant keyword}" in method names or comments
- Any references to {specific table name, config key, or concept}
- Any code path triggered by {specific condition, error type, or message field}

Add findings to the existing spec file.
```

This is targeted gap-filling, not re-extraction. One follow-up prompt, 30 seconds of your time.

---

## What Makes This Work

The key elements in the one-prompt approach that prevent Agent from producing shallow output:

1. **"Trace EVERY method call recursively"** — tells Agent to follow the full graph, not stop at the first layer
2. **"including into shared/common/utility classes"** — explicitly tells it not to ignore cross-package calls
3. **"find its current value in properties/yml files"** — forces config resolution
4. **"document EACH branch as a separate business rule"** — prevents Agent from summarizing conditionals
5. **"Confidence column"** — gives Agent permission to say "I'm not sure" instead of fabricating
6. **"Items Needing Verification section"** — gives Agent a place to put uncertainty, reducing hallucination
7. **Concrete output format with tables** — Agent produces structured output that maps directly to the implementation template
