package com.platform.common.error;

/**
 * Centralised error codes used across all microservices.
 * Format: {STAGE}_{CATEGORY}_{SEQUENCE}
 *
 * Stages: ING=Ingestion, VAL=Validation, PRC=Processing, DSP=Dispatch, RTY=Retry, SYS=System
 * Categories: DEDUP, SCHEMA, TRANSFORM, VENDOR, TIMEOUT, CIRCUIT, RATE, DB, KAFKA, AUTH, CONFIG
 */
public enum ErrorCode {

    // ── Ingestion ──
    ING_DEDUP_001("ING_DEDUP_001", "Duplicate message detected", false, Stage.INGESTION),
    ING_SCHEMA_001("ING_SCHEMA_001", "Invalid message schema or missing required fields", false, Stage.INGESTION),
    ING_SCHEMA_002("ING_SCHEMA_002", "Unsupported upstream identifier", false, Stage.INGESTION),
    ING_KAFKA_001("ING_KAFKA_001", "Kafka deserialization failure", false, Stage.INGESTION),

    // ── Validation ──
    VAL_SCHEMA_001("VAL_SCHEMA_001", "Business validation failed (upstream-specific)", false, Stage.VALIDATION),
    VAL_SCHEMA_002("VAL_SCHEMA_002", "Invalid recipient format", false, Stage.VALIDATION),
    VAL_SCHEMA_003("VAL_SCHEMA_003", "Template not found or inactive", false, Stage.VALIDATION),
    VAL_SCHEMA_004("VAL_SCHEMA_004", "Message expired (past expires_at)", false, Stage.VALIDATION),

    // ── Processing ──
    PRC_TRANSFORM_001("PRC_TRANSFORM_001", "Normalisation/transformation error", false, Stage.PROCESSING),
    PRC_CONFIG_001("PRC_CONFIG_001", "Missing upstream processor configuration", false, Stage.PROCESSING),
    PRC_DB_001("PRC_DB_001", "Event persistence failure", true, Stage.PROCESSING),

    // ── Dispatch ──
    DSP_VENDOR_001("DSP_VENDOR_001", "Vendor API client error (4xx)", false, Stage.DISPATCH),
    DSP_VENDOR_002("DSP_VENDOR_002", "Vendor API server error (5xx)", true, Stage.DISPATCH),
    DSP_VENDOR_003("DSP_VENDOR_003", "Vendor API unexpected response", true, Stage.DISPATCH),
    DSP_TIMEOUT_001("DSP_TIMEOUT_001", "Vendor API call timed out", true, Stage.DISPATCH),
    DSP_CIRCUIT_001("DSP_CIRCUIT_001", "Circuit breaker open; vendor unavailable", true, Stage.DISPATCH),
    DSP_RATE_001("DSP_RATE_001", "Rate limit exceeded for channel/vendor", true, Stage.DISPATCH),
    DSP_AUTH_001("DSP_AUTH_001", "Vendor authentication failed", false, Stage.DISPATCH),

    // ── Retry ──
    RTY_EXHAUSTED_001("RTY_EXHAUSTED_001", "Max retries exceeded; moved to DLT", false, Stage.RETRY),
    RTY_EXPIRED_001("RTY_EXPIRED_001", "Message expired during retry window", false, Stage.RETRY),

    // ── System / Infrastructure ──
    SYS_DB_001("SYS_DB_001", "Database connection failure", true, Stage.SYSTEM),
    SYS_DB_002("SYS_DB_002", "Optimistic lock conflict", true, Stage.SYSTEM),
    SYS_KAFKA_001("SYS_KAFKA_001", "Kafka producer send failure", true, Stage.SYSTEM),
    SYS_KAFKA_002("SYS_KAFKA_002", "Kafka transaction commit failure", true, Stage.SYSTEM),
    SYS_REDIS_001("SYS_REDIS_001", "Redis connection failure", true, Stage.SYSTEM),
    SYS_CONFIG_001("SYS_CONFIG_001", "Configuration load/refresh failure", false, Stage.SYSTEM);

    private final String code;
    private final String description;
    private final boolean retryable;
    private final Stage stage;

    ErrorCode(String code, String description, boolean retryable, Stage stage) {
        this.code = code;
        this.description = description;
        this.retryable = retryable;
        this.stage = stage;
    }

    public String getCode() { return code; }
    public String getDescription() { return description; }
    public boolean isRetryable() { return retryable; }
    public Stage getStage() { return stage; }

    public enum Stage {
        INGESTION, VALIDATION, PROCESSING, DISPATCH, RETRY, SYSTEM
    }
}
