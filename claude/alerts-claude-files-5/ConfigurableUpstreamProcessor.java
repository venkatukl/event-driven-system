package com.platform.processing.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.error.ErrorCode;
import com.platform.common.error.NonRetryableException;
import com.platform.common.factory.UpstreamProcessor;
import com.platform.common.model.ChannelType;
import com.platform.common.model.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Configuration-driven upstream processor that handles ANY upstream whose
 * business rules can be expressed as JSON config in upstream_config.config_json.
 *
 * PURPOSE: At 10+ upstreams, having a Java developer write a custom processor
 * for each one becomes a bottleneck. Most upstreams follow a pattern:
 *   - Validate required fields exist
 *   - Validate recipient format (phone/email)
 *   - Map to a channel based on rules
 *   - Set priority
 *   - Apply template
 *
 * This processor handles all of that via config. Custom UpstreamProcessor
 * implementations are only needed for genuinely complex business logic.
 *
 * ONBOARDING A NEW UPSTREAM (zero code deployment):
 *   1. INSERT into upstream_config with processor_bean = 'configurableProcessor'
 *   2. Set config_json with validation rules, channel mapping, etc.
 *   3. Create Kafka topic inbound.<upstream>
 *   4. Done — no Java code, no redeployment
 *
 * Example config_json:
 * {
 *   "requiredFields": ["recipient", "message", "transactionId"],
 *   "recipientFormat": "PHONE",
 *   "defaultChannel": "SMS",
 *   "channelRules": [
 *     {"field": "type", "equals": "OTP", "channel": "OTP"},
 *     {"field": "type", "equals": "EMAIL_CONFIRM", "channel": "EMAIL"}
 *   ],
 *   "priority": 5,
 *   "maxRetries": 3,
 *   "templateId": "GENERIC_ALERT_V1",
 *   "payloadFieldMapping": {
 *     "body": "message",
 *     "ref": "transactionId"
 *   }
 * }
 */
@Component("configurableProcessor")
public class ConfigurableUpstreamProcessor implements UpstreamProcessor {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableUpstreamProcessor.class);

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[1-9]\\d{7,14}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern DEVICE_TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9_:/-]{32,256}$");

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    // Cache of parsed config per upstream (refreshed on config change)
    private final Map<String, JsonNode> configCache = new ConcurrentHashMap<>();

    public ConfigurableUpstreamProcessor(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * This processor is special: it handles multiple upstream IDs.
     * The factory resolves it when upstream_config.processor_bean = 'configurableProcessor'.
     * We return a sentinel value; actual upstream matching is done by the factory.
     */
    @Override
    public String getUpstreamId() {
        return "__CONFIGURABLE__";
    }

    @Override
    public void validate(EventEnvelope envelope) {
        JsonNode config = getConfig(envelope.getUpstreamId());
        String corrId = envelope.getCorrelationId();

        // 1. Validate required fields in payload
        JsonNode requiredFields = config.get("requiredFields");
        if (requiredFields != null && requiredFields.isArray()) {
            JsonNode payload;
            try {
                payload = objectMapper.readTree(envelope.getPayload());
            } catch (Exception e) {
                throw new NonRetryableException(ErrorCode.VAL_SCHEMA_001, corrId,
                        "Payload is not valid JSON");
            }

            for (JsonNode field : requiredFields) {
                String fieldName = field.asText();
                if (!payload.has(fieldName) || payload.get(fieldName).isNull()) {
                    throw new NonRetryableException(ErrorCode.VAL_SCHEMA_001, corrId,
                            "Missing required field in payload: " + fieldName);
                }
            }
        }

        // 2. Validate recipient format
        String recipientFormat = getTextOrDefault(config, "recipientFormat", "PHONE");
        validateRecipientFormat(envelope.getRecipient(), recipientFormat, corrId);
    }

    @Override
    public EventEnvelope normalise(EventEnvelope envelope) {
        JsonNode config = getConfig(envelope.getUpstreamId());

        // 1. Resolve channel
        if (envelope.getChannel() == null) {
            ChannelType channel = resolveChannel(envelope, config);
            envelope.setChannel(channel);
        }

        // 2. Set priority
        int priority = getIntOrDefault(config, "priority", 5);
        envelope.setPriority(priority);

        // 3. Set max retries
        int maxRetries = getIntOrDefault(config, "maxRetries", 3);
        envelope.setMaxRetries(maxRetries);

        // 4. Set template
        String templateId = getTextOrDefault(config, "templateId", null);
        if (templateId != null && envelope.getTemplateId() == null) {
            envelope.setTemplateId(templateId);
        }

        return envelope;
    }

    // ── Channel Resolution from Rules ──

    private ChannelType resolveChannel(EventEnvelope envelope, JsonNode config) {
        // Check channel rules first
        JsonNode rules = config.get("channelRules");
        if (rules != null && rules.isArray()) {
            try {
                JsonNode payload = objectMapper.readTree(envelope.getPayload());
                for (JsonNode rule : rules) {
                    String field = rule.get("field").asText();
                    String expectedValue = rule.get("equals").asText();
                    String channelName = rule.get("channel").asText();

                    if (payload.has(field) && expectedValue.equals(payload.get(field).asText())) {
                        return ChannelType.valueOf(channelName);
                    }
                }
            } catch (Exception e) {
                log.warn("Error evaluating channel rules for upstream={}: {}",
                        envelope.getUpstreamId(), e.getMessage());
            }
        }

        // Fall back to default channel
        String defaultChannel = getTextOrDefault(config, "defaultChannel", "SMS");
        return ChannelType.valueOf(defaultChannel);
    }

    // ── Recipient Validation ──

    private void validateRecipientFormat(String recipient, String format, String corrId) {
        if (recipient == null || recipient.isBlank()) {
            throw new NonRetryableException(ErrorCode.VAL_SCHEMA_002, corrId, "Recipient is empty");
        }

        boolean valid = switch (format) {
            case "PHONE" -> PHONE_PATTERN.matcher(recipient).matches();
            case "EMAIL" -> EMAIL_PATTERN.matcher(recipient).matches();
            case "DEVICE_TOKEN" -> DEVICE_TOKEN_PATTERN.matcher(recipient).matches();
            case "URL" -> recipient.startsWith("http://") || recipient.startsWith("https://");
            case "ANY" -> true;
            default -> {
                log.warn("Unknown recipient format '{}', accepting any", format);
                yield true;
            }
        };

        if (!valid) {
            throw new NonRetryableException(ErrorCode.VAL_SCHEMA_002, corrId,
                    "Recipient '" + maskRecipient(recipient) + "' does not match format: " + format);
        }
    }

    // ── Config Cache ──

    private JsonNode getConfig(String upstreamId) {
        return configCache.computeIfAbsent(upstreamId, id -> {
            String configJson = jdbcTemplate.queryForObject(
                    "SELECT config_json FROM upstream_config WHERE upstream_id = ? AND is_active = 1",
                    String.class, id);
            if (configJson == null) {
                throw new NonRetryableException(ErrorCode.PRC_CONFIG_001, null,
                        "No config_json found for configurable upstream: " + id);
            }
            try {
                return objectMapper.readTree(configJson);
            } catch (Exception e) {
                throw new NonRetryableException(ErrorCode.PRC_CONFIG_001, null,
                        "Invalid config_json for upstream: " + id);
            }
        });
    }

    /**
     * Call this to refresh config cache (e.g., from @RefreshScope or scheduled task).
     */
    public void refreshConfig(String upstreamId) {
        configCache.remove(upstreamId);
    }

    public void refreshAllConfigs() {
        configCache.clear();
    }

    // ── Helpers ──

    private String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : defaultValue;
    }

    private int getIntOrDefault(JsonNode node, String field, int defaultValue) {
        return node.has(field) && node.get(field).isInt() ? node.get(field).asInt() : defaultValue;
    }

    private String maskRecipient(String recipient) {
        if (recipient.length() <= 4) return "****";
        return recipient.substring(0, 2) + "****" + recipient.substring(recipient.length() - 2);
    }
}
