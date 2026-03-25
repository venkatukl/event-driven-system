// ============================================================================
// CBD Alerts Platform — MongoDB Initialization Script
// ============================================================================
// Run with: mongosh --host <host> --port <port> -u <user> -p <password> < init-mongodb.js
//
// Collections: 9
// TTL: 90 days (7,776,000 seconds) on all operational collections
// Write concern: majority for event writes, 1 for audit/metrics
// ============================================================================

const DB_NAME = "cbd_alerts";
const TTL_90_DAYS = 7776000;

db = db.getSiblingDB(DB_NAME);

print("============================================================================");
print("CBD Alerts Platform — MongoDB Initialization");
print("Database: " + DB_NAME);
print("Started: " + new Date().toISOString());
print("============================================================================");


// ============================================================================
// 1. events — Master event document (payload + outbox embedded)
// ============================================================================
print("\n[1/9] Creating collection: events");

db.createCollection("events", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["_id", "upstreamId", "eventType", "channel", "priority", "status", "payload", "createdAt"],
      properties: {
        _id:                { bsonType: "string", description: "Event ID (e.g., evt_abc123)" },
        upstreamId:         { bsonType: "string", description: "Source upstream identifier" },
        eventType:          { bsonType: "string", description: "Event type (OTP, STATEMENT, MARKETING, etc.)" },
        channel:            { bsonType: "string", enum: ["SMS", "VOICE", "EMAIL", "PUSH", "WEBHOOK"], description: "Dispatch channel" },
        priority:           { bsonType: "string", enum: ["HIGH", "MEDIUM", "LOW"], description: "End-to-end priority tier" },
        status:             { bsonType: "string", enum: ["RECEIVED", "VALIDATED", "PROCESSED", "DISPATCHED", "FAILED", "RETRIED", "DLT"], description: "Current event status" },
        errorCode:          { bsonType: ["string", "null"], description: "Error code (ING-xxx, PRC-xxx, DSP-xxx)" },

        // Payload (replaces Oracle event_payloads table — no hot/cold split needed)
        payload:            { bsonType: "object", description: "Raw upstream payload (upstream-specific structure)" },
        normalizedPayload:  { bsonType: ["object", "null"], description: "Normalized payload after processing pipeline" },

        // Embedded outbox (replaces Oracle event_outbox table)
        outboxStatus:       { bsonType: ["string", "null"], enum: ["PENDING", "PUBLISHED", null], description: "Outbox poller status" },
        outboxTarget:       { bsonType: ["string", "null"], description: "Target Kafka topic (e.g., dispatch.high)" },
        outboxPublishedAt:  { bsonType: ["date", "null"], description: "When outbox poller published to Kafka" },
        leasedBy:           { bsonType: ["string", "null"], description: "Pod ID holding the outbox lease" },
        leaseExpiry:        { bsonType: ["date", "null"], description: "Lease expiry timestamp for outbox poller" },

        // Dispatch result (updated by dispatch service)
        dispatchedAt:       { bsonType: ["date", "null"] },
        vendorId:           { bsonType: ["string", "null"], description: "Vendor that handled dispatch" },
        vendorResponseCode: { bsonType: ["int", "null"] },
        vendorLatencyMs:    { bsonType: ["int", "null"] },
        vendorMessageId:    { bsonType: ["string", "null"], description: "Vendor-side message ID for idempotency tracking" },

        // Metadata
        correlationId:      { bsonType: ["string", "null"], description: "End-to-end correlation ID" },
        sourceDc:           { bsonType: ["string", "null"], description: "Originating data centre" },
        retryCount:         { bsonType: "int", description: "Number of dispatch retry attempts" },
        createdAt:          { bsonType: "date" },
        processedAt:        { bsonType: ["date", "null"] },
        updatedAt:          { bsonType: ["date", "null"] }
      }
    }
  },
  validationLevel: "moderate",
  validationAction: "warn"
});

// Primary query: status dashboard, recent events
db.events.createIndex(
  { "status": 1, "createdAt": -1 },
  { name: "idx_events_status_created", background: true }
);

// Per-upstream queries: upstream dashboard, SLA
db.events.createIndex(
  { "upstreamId": 1, "status": 1, "createdAt": -1 },
  { name: "idx_events_upstream_status", background: true }
);

// Correlation ID lookup: end-to-end tracing
db.events.createIndex(
  { "correlationId": 1 },
  { name: "idx_events_correlation", background: true, sparse: true }
);

// Outbox poller: lease-based polling for unpublished events
// Partial index — only indexes documents where outboxStatus = "PENDING"
db.events.createIndex(
  { "outboxStatus": 1, "priority": 1, "createdAt": 1 },
  { 
    name: "idx_events_outbox_poller",
    partialFilterExpression: { "outboxStatus": "PENDING" },
    background: true
  }
);

// Lease expiry: for outbox poller to reclaim expired leases
db.events.createIndex(
  { "leasedBy": 1, "leaseExpiry": 1 },
  {
    name: "idx_events_lease",
    partialFilterExpression: { "outboxStatus": "PENDING", "leasedBy": { $ne: null } },
    background: true
  }
);

// TTL: auto-expire after 90 days
db.events.createIndex(
  { "createdAt": 1 },
  { name: "idx_events_ttl", expireAfterSeconds: TTL_90_DAYS, background: true }
);

// Upstream-specific partial indexes (add per upstream as needed)
// These allow querying on upstream-specific payload fields without indexing all events
db.events.createIndex(
  { "payload.accountNumber": 1 },
  { 
    name: "idx_events_banking_account",
    partialFilterExpression: { "upstreamId": "BANKING" },
    background: true,
    sparse: true
  }
);

db.events.createIndex(
  { "payload.policyId": 1 },
  {
    name: "idx_events_insurance_policy",
    partialFilterExpression: { "upstreamId": "INSURANCE" },
    background: true,
    sparse: true
  }
);

print("  ✓ events: collection + 8 indexes created");


// ============================================================================
// 2. eventDispatches — Append-only dispatch attempt history
// ============================================================================
print("\n[2/9] Creating collection: eventDispatches");

db.createCollection("eventDispatches", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["eventId", "attemptNumber", "channel", "vendorId", "createdAt"],
      properties: {
        eventId:        { bsonType: "string", description: "Reference to events._id" },
        attemptNumber:  { bsonType: "int", description: "Dispatch attempt (1, 2, 3...)" },
        channel:        { bsonType: "string" },
        vendorId:       { bsonType: "string" },
        statusCode:     { bsonType: ["int", "null"], description: "Vendor HTTP status code" },
        latencyMs:      { bsonType: ["int", "null"], description: "Vendor API call latency" },
        vendorMessageId:{ bsonType: ["string", "null"], description: "Vendor-side reference ID" },
        errorCode:      { bsonType: ["string", "null"] },
        errorDetail:    { bsonType: ["string", "null"], description: "Truncated error message" },
        createdAt:      { bsonType: "date" }
      }
    }
  },
  validationLevel: "moderate",
  validationAction: "warn"
});

// Lookup: all dispatch attempts for an event
db.eventDispatches.createIndex(
  { "eventId": 1, "attemptNumber": 1 },
  { name: "idx_dispatches_event_attempt", background: true }
);

// Analytics: vendor performance
db.eventDispatches.createIndex(
  { "vendorId": 1, "createdAt": -1 },
  { name: "idx_dispatches_vendor", background: true }
);

// TTL: auto-expire after 90 days
db.eventDispatches.createIndex(
  { "createdAt": 1 },
  { name: "idx_dispatches_ttl", expireAfterSeconds: TTL_90_DAYS, background: true }
);

print("  ✓ eventDispatches: collection + 3 indexes created");


// ============================================================================
// 3. eventRetries — Active retry tracking
// ============================================================================
print("\n[3/9] Creating collection: eventRetries");

db.createCollection("eventRetries", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["_id", "upstreamId", "channel", "priority", "attemptCount", "maxAttempts", "nextRetryAt"],
      properties: {
        _id:                  { bsonType: "string", description: "Same as event_id — one retry record per event" },
        upstreamId:           { bsonType: "string" },
        channel:              { bsonType: "string" },
        priority:             { bsonType: "string" },
        attemptCount:         { bsonType: "int" },
        maxAttempts:          { bsonType: "int" },
        nextRetryAt:          { bsonType: "date", description: "When to attempt next retry" },
        backoffState:         { bsonType: "string", description: "Current backoff interval (30s, 1m, 5m, 15m)" },
        lastErrorCode:        { bsonType: ["string", "null"] },
        originalDispatchTopic:{ bsonType: "string", description: "Topic to re-dispatch to" },
        createdAt:            { bsonType: "date" },
        updatedAt:            { bsonType: "date" }
      }
    }
  },
  validationLevel: "moderate",
  validationAction: "warn"
});

// Retry poller: find events ready for retry
db.eventRetries.createIndex(
  { "nextRetryAt": 1, "attemptCount": 1 },
  { name: "idx_retries_next_at", background: true }
);

// TTL: auto-expire after 90 days
db.eventRetries.createIndex(
  { "createdAt": 1 },
  { name: "idx_retries_ttl", expireAfterSeconds: TTL_90_DAYS, background: true }
);

print("  ✓ eventRetries: collection + 2 indexes created");


// ============================================================================
// 4. dedupLog — Deduplication tier 2 (durable fallback)
// ============================================================================
print("\n[4/9] Creating collection: dedupLog");

db.createCollection("dedupLog");

// UNIQUE index: the core dedup guarantee
// Format: {upstreamId}:{messageId}:{eventType}
db.dedupLog.createIndex(
  { "dedupKey": 1 },
  { name: "idx_dedup_key", unique: true, background: true }
);

// Lookup by eventId (for investigation)
db.dedupLog.createIndex(
  { "eventId": 1 },
  { name: "idx_dedup_event", background: true }
);

// TTL: auto-expire after 90 days
db.dedupLog.createIndex(
  { "createdAt": 1 },
  { name: "idx_dedup_ttl", expireAfterSeconds: TTL_90_DAYS, background: true }
);

print("  ✓ dedupLog: collection + 3 indexes (including unique dedupKey)");


// ============================================================================
// 5. auditLog — Full lifecycle trail
// ============================================================================
print("\n[5/9] Creating collection: auditLog");

db.createCollection("auditLog");

// Lookup: full lifecycle for an event
db.auditLog.createIndex(
  { "eventId": 1, "timestamp": 1 },
  { name: "idx_audit_event_time", background: true }
);

// Dashboard: per-upstream audit trail
db.auditLog.createIndex(
  { "upstreamId": 1, "timestamp": -1 },
  { name: "idx_audit_upstream", background: true }
);

// Error investigation: find failures by error code
db.auditLog.createIndex(
  { "errorCode": 1, "timestamp": -1 },
  {
    name: "idx_audit_errors",
    partialFilterExpression: { "errorCode": { $ne: null } },
    background: true
  }
);

// TTL: auto-expire after 90 days
db.auditLog.createIndex(
  { "timestamp": 1 },
  { name: "idx_audit_ttl", expireAfterSeconds: TTL_90_DAYS, background: true }
);

print("  ✓ auditLog: collection + 4 indexes created");


// ============================================================================
// 6. upstreamMetrics — Hourly aggregated metrics
// ============================================================================
print("\n[6/9] Creating collection: upstreamMetrics");

db.createCollection("upstreamMetrics");

// Unique compound: one document per upstream × channel × hour
db.upstreamMetrics.createIndex(
  { "upstreamId": 1, "channel": 1, "metricHour": 1 },
  { name: "idx_metrics_compound", unique: true, background: true }
);

// Time-range queries for dashboards
db.upstreamMetrics.createIndex(
  { "metricHour": -1 },
  { name: "idx_metrics_hour", background: true }
);

print("  ✓ upstreamMetrics: collection + 2 indexes created");


// ============================================================================
// 7. upstreamConfig — Upstream registration & configuration
// ============================================================================
print("\n[7/9] Creating collection: upstreamConfig");

db.createCollection("upstreamConfig", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["_id", "displayName", "enabled", "defaultPriority", "channelMappings"],
      properties: {
        _id:              { bsonType: "string", description: "Upstream ID (e.g., BANKING)" },
        displayName:      { bsonType: "string" },
        enabled:          { bsonType: "bool" },
        defaultPriority:  { bsonType: "string", enum: ["HIGH", "MEDIUM", "LOW"] },
        channelMappings:  { bsonType: "object", description: "Event type → channel mapping" },
        rateLimits:       { bsonType: ["object", "null"], description: "Per-channel rate limits" },
        processorClass:   { bsonType: "string", description: "Spring bean name for upstream processor" },
        inboundChannel:   { bsonType: "string", enum: ["MQ", "KAFKA", "FILE"], description: "How this upstream sends messages" },
        inboundConfig:    { bsonType: ["object", "null"], description: "Channel-specific config (queue name, topic, directory)" },
        contactTeam:      { bsonType: ["string", "null"] },
        onboardedAt:      { bsonType: "date" },
        updatedAt:        { bsonType: "date" }
      }
    }
  },
  validationLevel: "strict",
  validationAction: "error"
});

print("  ✓ upstreamConfig: collection created (PK = _id = upstreamId)");


// ============================================================================
// 8. channelVendorConfig — Vendor details per channel
// ============================================================================
print("\n[8/9] Creating collection: channelVendorConfig");

db.createCollection("channelVendorConfig", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["channel", "vendorId", "enabled", "apiEndpoint", "credentialRef"],
      properties: {
        channel:          { bsonType: "string" },
        vendorId:         { bsonType: "string" },
        priority:         { bsonType: "int", description: "Failover order (1 = primary)" },
        enabled:          { bsonType: "bool" },
        apiEndpoint:      { bsonType: "string" },
        credentialRef:    { bsonType: "string", description: "Vault reference — NOT credentials" },
        timeoutMs:        { bsonType: "int" },
        rateLimitPerSecond: { bsonType: ["int", "null"] },
        circuitBreakerConfig: {
          bsonType: ["object", "null"],
          properties: {
            failureRateThreshold:       { bsonType: "int" },
            waitDurationOpenStateMs:    { bsonType: "int" },
            slidingWindowSize:          { bsonType: "int" },
            permittedInHalfOpen:        { bsonType: "int" }
          }
        },
        updatedAt: { bsonType: "date" }
      }
    }
  },
  validationLevel: "strict",
  validationAction: "error"
});

db.channelVendorConfig.createIndex(
  { "channel": 1, "vendorId": 1 },
  { name: "idx_vendor_channel", unique: true, background: true }
);

db.channelVendorConfig.createIndex(
  { "channel": 1, "priority": 1 },
  { name: "idx_vendor_failover", background: true }
);

print("  ✓ channelVendorConfig: collection + 2 indexes created");


// ============================================================================
// 9. pipelineConfig — Configurable processing steps
// ============================================================================
print("\n[9/9] Creating collection: pipelineConfig");

db.createCollection("pipelineConfig", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["upstreamId", "phase", "stepOrder", "stepName", "enabled", "onFailure"],
      properties: {
        upstreamId:     { bsonType: "string" },
        phase:          { bsonType: "string", enum: ["PRE", "PROCESS", "POST"], description: "Pipeline phase" },
        stepOrder:      { bsonType: "int", description: "Execution order within phase" },
        stepName:       { bsonType: "string", description: "Spring bean name of ProcessingStep" },
        stepConfig:     { bsonType: ["object", "null"], description: "Step-specific JSON configuration" },
        enabled:        { bsonType: "bool" },
        conditionExpr:  { bsonType: ["string", "null"], description: "SpEL expression for conditional execution" },
        onFailure:      { bsonType: "string", enum: ["REJECT", "SKIP", "CONTINUE"], description: "Failure handling strategy" },
        updatedAt:      { bsonType: "date" }
      }
    }
  },
  validationLevel: "strict",
  validationAction: "error"
});

db.pipelineConfig.createIndex(
  { "upstreamId": 1, "phase": 1, "stepOrder": 1 },
  { name: "idx_pipeline_upstream_phase_order", unique: true, background: true }
);

print("  ✓ pipelineConfig: collection + 1 unique index created");


// ============================================================================
// SEED DATA — Upstream configurations
// ============================================================================
print("\n============================================================================");
print("Seeding reference data...");
print("============================================================================");

// --- Upstream configs ---
db.upstreamConfig.insertMany([
  {
    _id: "BANKING",
    displayName: "Core Banking System",
    enabled: true,
    defaultPriority: "MEDIUM",
    channelMappings: {
      "OTP": "SMS",
      "FRAUD_ALERT": "SMS",
      "STATEMENT": "EMAIL",
      "MARKETING": "EMAIL",
      "DEFAULT": "SMS"
    },
    rateLimits: { "SMS": 100, "EMAIL": 500, "VOICE": 20 },
    processorClass: "configurableUpstreamProcessor",
    inboundChannel: "KAFKA",
    inboundConfig: { topic: "inbound.banking" },
    contactTeam: "banking-platform@corp.com",
    onboardedAt: new Date(),
    updatedAt: new Date()
  },
  {
    _id: "INSURANCE",
    displayName: "Insurance Platform",
    enabled: true,
    defaultPriority: "MEDIUM",
    channelMappings: {
      "CLAIM_UPDATE": "SMS",
      "POLICY_RENEWAL": "EMAIL",
      "MARKETING": "EMAIL",
      "DEFAULT": "EMAIL"
    },
    rateLimits: { "SMS": 50, "EMAIL": 200 },
    processorClass: "configurableUpstreamProcessor",
    inboundChannel: "KAFKA",
    inboundConfig: { topic: "inbound.insurance" },
    contactTeam: "insurance-tech@corp.com",
    onboardedAt: new Date(),
    updatedAt: new Date()
  },
  {
    _id: "CARDS",
    displayName: "Credit Cards System",
    enabled: true,
    defaultPriority: "HIGH",
    channelMappings: {
      "TXN_ALERT": "SMS",
      "OTP": "SMS",
      "STATEMENT": "EMAIL",
      "DEFAULT": "SMS"
    },
    rateLimits: { "SMS": 200, "EMAIL": 100 },
    processorClass: "configurableUpstreamProcessor",
    inboundChannel: "KAFKA",
    inboundConfig: { topic: "inbound.cards" },
    contactTeam: "cards-platform@corp.com",
    onboardedAt: new Date(),
    updatedAt: new Date()
  },
  {
    _id: "LOANS",
    displayName: "Loans Management System",
    enabled: true,
    defaultPriority: "LOW",
    channelMappings: {
      "EMI_REMINDER": "SMS",
      "DISBURSEMENT": "SMS",
      "MARKETING": "EMAIL",
      "DEFAULT": "SMS"
    },
    rateLimits: { "SMS": 50, "EMAIL": 100 },
    processorClass: "configurableUpstreamProcessor",
    inboundChannel: "MQ",
    inboundConfig: { queueName: "LOANS.ALERTS.OUTBOUND" },
    contactTeam: "loans-tech@corp.com",
    onboardedAt: new Date(),
    updatedAt: new Date()
  }
]);

print("  ✓ Inserted 4 upstream configs (BANKING, INSURANCE, CARDS, LOANS)");

// --- Channel vendor configs ---
db.channelVendorConfig.insertMany([
  {
    channel: "SMS",
    vendorId: "SMS_PRIMARY",
    priority: 1,
    enabled: true,
    apiEndpoint: "https://api-gateway.corp.com/sms/v1/send",
    credentialRef: "vault:secret/vendors/sms-primary",
    timeoutMs: 5000,
    rateLimitPerSecond: 200,
    circuitBreakerConfig: {
      failureRateThreshold: 50,
      waitDurationOpenStateMs: 60000,
      slidingWindowSize: 20,
      permittedInHalfOpen: 5
    },
    updatedAt: new Date()
  },
  {
    channel: "SMS",
    vendorId: "SMS_SECONDARY",
    priority: 2,
    enabled: true,
    apiEndpoint: "https://api-gateway.corp.com/sms/v2/send",
    credentialRef: "vault:secret/vendors/sms-secondary",
    timeoutMs: 5000,
    rateLimitPerSecond: 100,
    circuitBreakerConfig: {
      failureRateThreshold: 50,
      waitDurationOpenStateMs: 60000,
      slidingWindowSize: 20,
      permittedInHalfOpen: 5
    },
    updatedAt: new Date()
  },
  {
    channel: "VOICE",
    vendorId: "VOICE_PRIMARY",
    priority: 1,
    enabled: true,
    apiEndpoint: "https://api-gateway.corp.com/voice/v1/call",
    credentialRef: "vault:secret/vendors/voice-primary",
    timeoutMs: 30000,
    rateLimitPerSecond: 20,
    circuitBreakerConfig: {
      failureRateThreshold: 50,
      waitDurationOpenStateMs: 120000,
      slidingWindowSize: 10,
      permittedInHalfOpen: 3
    },
    updatedAt: new Date()
  },
  {
    channel: "EMAIL",
    vendorId: "EMAIL_PRIMARY",
    priority: 1,
    enabled: true,
    apiEndpoint: "https://api-gateway.corp.com/email/v1/send",
    credentialRef: "vault:secret/vendors/email-primary",
    timeoutMs: 10000,
    rateLimitPerSecond: 500,
    circuitBreakerConfig: {
      failureRateThreshold: 50,
      waitDurationOpenStateMs: 60000,
      slidingWindowSize: 20,
      permittedInHalfOpen: 5
    },
    updatedAt: new Date()
  }
]);

print("  ✓ Inserted 4 channel vendor configs (SMS×2, VOICE, EMAIL)");

// --- Pipeline configs for BANKING upstream ---
db.pipelineConfig.insertMany([
  // PRE-PROCESSING (enrichment)
  {
    upstreamId: "BANKING",
    phase: "PRE",
    stepOrder: 10,
    stepName: "requiredFieldsValidator",
    stepConfig: {
      fields: ["customerId", "accountNumber", "channel", "eventType"]
    },
    enabled: true,
    conditionExpr: null,
    onFailure: "REJECT",
    updatedAt: new Date()
  },
  {
    upstreamId: "BANKING",
    phase: "PRE",
    stepOrder: 20,
    stepName: "fieldFormatValidator",
    stepConfig: {
      rules: {
        "mobile": "^\\+91[0-9]{10}$",
        "email": "^.+@.+\\..+$"
      }
    },
    enabled: true,
    conditionExpr: null,
    onFailure: "REJECT",
    updatedAt: new Date()
  },
  {
    upstreamId: "BANKING",
    phase: "PRE",
    stepOrder: 30,
    stepName: "customerProfileEnricher",
    stepConfig: {
      source: "ENTITLEMENTS",
      fields: ["name", "preferredLanguage", "segment"]
    },
    enabled: true,
    conditionExpr: null,
    onFailure: "CONTINUE",
    updatedAt: new Date()
  },
  {
    upstreamId: "BANKING",
    phase: "PRE",
    stepOrder: 40,
    stepName: "templateResolver",
    stepConfig: {
      source: "CMS",
      templateField: "templateId"
    },
    enabled: true,
    conditionExpr: null,
    onFailure: "REJECT",
    updatedAt: new Date()
  },

  // PROCESSING (transformation)
  {
    upstreamId: "BANKING",
    phase: "PROCESS",
    stepOrder: 10,
    stepName: "fieldMapper",
    stepConfig: {
      mappings: {
        "src_cust_id": "customerId",
        "src_acct": "accountNumber",
        "src_phone": "mobile",
        "src_email": "email"
      }
    },
    enabled: true,
    conditionExpr: null,
    onFailure: "REJECT",
    updatedAt: new Date()
  },
  {
    upstreamId: "BANKING",
    phase: "PROCESS",
    stepOrder: 20,
    stepName: "dateFormatNormalizer",
    stepConfig: {
      fields: ["transactionDate"],
      inputFormat: "dd/MM/yyyy",
      outputFormat: "ISO8601"
    },
    enabled: true,
    conditionExpr: null,
    onFailure: "CONTINUE",
    updatedAt: new Date()
  },
  {
    upstreamId: "BANKING",
    phase: "PROCESS",
    stepOrder: 30,
    stepName: "priorityOverrideStep",
    stepConfig: {
      rules: [
        { eventType: "OTP", priority: "HIGH" },
        { eventType: "FRAUD_ALERT", priority: "HIGH" },
        { eventType: "STATEMENT", priority: "LOW" },
        { eventType: "MARKETING", priority: "LOW" }
      ]
    },
    enabled: true,
    conditionExpr: null,
    onFailure: "CONTINUE",
    updatedAt: new Date()
  },
  {
    upstreamId: "BANKING",
    phase: "PROCESS",
    stepOrder: 40,
    stepName: "channelRouter",
    stepConfig: {
      rules: [
        { eventType: "OTP", channel: "SMS" },
        { eventType: "FRAUD_ALERT", channel: "SMS" },
        { eventType: "STATEMENT", channel: "EMAIL" },
        { "default": "SMS" }
      ]
    },
    enabled: true,
    conditionExpr: null,
    onFailure: "REJECT",
    updatedAt: new Date()
  },

  // POST-PROCESSING (validation)
  {
    upstreamId: "BANKING",
    phase: "POST",
    stepOrder: 10,
    stepName: "fieldMasker",
    stepConfig: {
      fields: ["aadhaar", "pan"],
      strategy: "PARTIAL",
      visibleChars: 4
    },
    enabled: true,
    conditionExpr: "#context.envelope.channel != 'WEBHOOK'",
    onFailure: "CONTINUE",
    updatedAt: new Date()
  },
  {
    upstreamId: "BANKING",
    phase: "POST",
    stepOrder: 20,
    stepName: "requiredFieldsValidator",
    stepConfig: {
      fields: ["customerId", "channel", "priority", "normalizedPayload.recipientId", "normalizedPayload.templateId"]
    },
    enabled: true,
    conditionExpr: null,
    onFailure: "REJECT",
    updatedAt: new Date()
  },
  {
    upstreamId: "BANKING",
    phase: "POST",
    stepOrder: 30,
    stepName: "dispatchTopicResolver",
    stepConfig: {
      topicPattern: "dispatch.{priority}"
    },
    enabled: true,
    conditionExpr: null,
    onFailure: "REJECT",
    updatedAt: new Date()
  }
]);

print("  ✓ Inserted 11 pipeline config steps for BANKING (4 PRE + 4 PROCESS + 3 POST)");

// --- Pipeline configs for INSURANCE upstream (simpler) ---
db.pipelineConfig.insertMany([
  {
    upstreamId: "INSURANCE",
    phase: "PRE",
    stepOrder: 10,
    stepName: "requiredFieldsValidator",
    stepConfig: { fields: ["customerId", "policyId", "eventType"] },
    enabled: true, conditionExpr: null, onFailure: "REJECT", updatedAt: new Date()
  },
  {
    upstreamId: "INSURANCE",
    phase: "PRE",
    stepOrder: 20,
    stepName: "customerProfileEnricher",
    stepConfig: { source: "ENTITLEMENTS", fields: ["name", "preferredLanguage"] },
    enabled: true, conditionExpr: null, onFailure: "CONTINUE", updatedAt: new Date()
  },
  {
    upstreamId: "INSURANCE",
    phase: "PRE",
    stepOrder: 30,
    stepName: "templateResolver",
    stepConfig: { source: "CMS", templateField: "templateId" },
    enabled: true, conditionExpr: null, onFailure: "REJECT", updatedAt: new Date()
  },
  {
    upstreamId: "INSURANCE",
    phase: "PROCESS",
    stepOrder: 10,
    stepName: "fieldMapper",
    stepConfig: { mappings: { "policy_no": "policyId", "cust_no": "customerId", "phone": "mobile" } },
    enabled: true, conditionExpr: null, onFailure: "REJECT", updatedAt: new Date()
  },
  {
    upstreamId: "INSURANCE",
    phase: "PROCESS",
    stepOrder: 20,
    stepName: "channelRouter",
    stepConfig: { rules: [{ eventType: "CLAIM_UPDATE", channel: "SMS" }, { "default": "EMAIL" }] },
    enabled: true, conditionExpr: null, onFailure: "REJECT", updatedAt: new Date()
  },
  {
    upstreamId: "INSURANCE",
    phase: "POST",
    stepOrder: 10,
    stepName: "requiredFieldsValidator",
    stepConfig: { fields: ["customerId", "channel", "priority"] },
    enabled: true, conditionExpr: null, onFailure: "REJECT", updatedAt: new Date()
  },
  {
    upstreamId: "INSURANCE",
    phase: "POST",
    stepOrder: 20,
    stepName: "dispatchTopicResolver",
    stepConfig: { topicPattern: "dispatch.{priority}" },
    enabled: true, conditionExpr: null, onFailure: "REJECT", updatedAt: new Date()
  }
]);

print("  ✓ Inserted 7 pipeline config steps for INSURANCE (3 PRE + 2 PROCESS + 2 POST)");


// ============================================================================
// VERIFICATION
// ============================================================================
print("\n============================================================================");
print("Verification");
print("============================================================================");

const collections = db.getCollectionNames().sort();
print("\nCollections (" + collections.length + "):");
collections.forEach(c => {
  const count = db.getCollection(c).countDocuments();
  const indexes = db.getCollection(c).getIndexes().length;
  print("  " + c.padEnd(25) + "docs: " + String(count).padStart(4) + "  indexes: " + indexes);
});

print("\nIndex summary:");
collections.forEach(c => {
  const indexes = db.getCollection(c).getIndexes();
  indexes.forEach(idx => {
    if (idx.name !== "_id_") {
      let flags = [];
      if (idx.unique) flags.push("UNIQUE");
      if (idx.expireAfterSeconds) flags.push("TTL:" + idx.expireAfterSeconds + "s");
      if (idx.partialFilterExpression) flags.push("PARTIAL");
      if (idx.sparse) flags.push("SPARSE");
      print("  " + c.padEnd(25) + idx.name.padEnd(40) + flags.join(" | "));
    }
  });
});

print("\n============================================================================");
print("Initialization complete: " + new Date().toISOString());
print("  Collections: " + collections.length);
print("  Database: " + DB_NAME);
print("============================================================================");
