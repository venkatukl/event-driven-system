package com.platform.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests validate that upstream message schemas are compatible
 * with our ingestion and processing services.
 *
 * RUN THESE IN CI:
 *   - When an upstream changes their contract (schema file)
 *   - When we change our EventEnvelope model
 *   - Before every release
 *
 * If a contract test fails, it means either:
 *   1. The upstream changed their format (they need to update the contract)
 *   2. We changed our model in a breaking way (we need to fix it)
 *
 * Add dependency: implementation("com.networknt:json-schema-validator:1.0.87")
 */
class BankingContractTest {

    private static JsonSchema schema;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void loadSchema() throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        try (InputStream is = BankingContractTest.class.getResourceAsStream(
                "/contracts/banking/message-schema-v1.json")) {
            schema = factory.getSchema(is);
        }
    }

    @Test
    void validMessage_passesContractValidation() throws Exception {
        String json = """
                {
                    "upstreamMsgId": "TXN-2025-001-abc123",
                    "recipient": "+919876543210",
                    "payload": {
                        "transactionId": "TXN-001",
                        "amount": 5000.00,
                        "currency": "INR",
                        "message": "Rs 5,000 debited from A/c XXXX1234"
                    }
                }
                """;

        Set<ValidationMessage> errors = schema.validate(mapper.readTree(json));
        assertThat(errors).isEmpty();
    }

    @Test
    void missingRecipient_failsContractValidation() throws Exception {
        String json = """
                {
                    "upstreamMsgId": "TXN-2025-002",
                    "payload": {
                        "transactionId": "TXN-002",
                        "message": "Test message"
                    }
                }
                """;

        Set<ValidationMessage> errors = schema.validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
        assertThat(errors.toString()).contains("recipient");
    }

    @Test
    void invalidPhoneFormat_failsContractValidation() throws Exception {
        String json = """
                {
                    "upstreamMsgId": "TXN-2025-003",
                    "recipient": "not-a-phone-number",
                    "payload": {
                        "transactionId": "TXN-003",
                        "message": "Test message"
                    }
                }
                """;

        Set<ValidationMessage> errors = schema.validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
        assertThat(errors.toString()).contains("pattern");
    }

    @Test
    void missingTransactionId_failsContractValidation() throws Exception {
        String json = """
                {
                    "upstreamMsgId": "TXN-2025-004",
                    "recipient": "+919876543210",
                    "payload": {
                        "message": "Missing transaction ID"
                    }
                }
                """;

        Set<ValidationMessage> errors = schema.validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
        assertThat(errors.toString()).contains("transactionId");
    }

    @Test
    void extraFieldsRejected_contractIsStrict() throws Exception {
        String json = """
                {
                    "upstreamMsgId": "TXN-2025-005",
                    "recipient": "+919876543210",
                    "payload": {
                        "transactionId": "TXN-005",
                        "message": "Test"
                    },
                    "unexpectedField": "should fail"
                }
                """;

        Set<ValidationMessage> errors = schema.validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
        assertThat(errors.toString()).contains("additionalProperties");
    }

    @Test
    void optionalFieldsCanBeNull() throws Exception {
        String json = """
                {
                    "upstreamMsgId": "TXN-2025-006",
                    "recipient": "+919876543210",
                    "subject": null,
                    "channel": null,
                    "priority": null,
                    "templateId": null,
                    "scheduledAt": null,
                    "expiresAt": null,
                    "payload": {
                        "transactionId": "TXN-006",
                        "message": "All optional fields null"
                    }
                }
                """;

        Set<ValidationMessage> errors = schema.validate(mapper.readTree(json));
        assertThat(errors).isEmpty();
    }

    @Test
    void channelEnumValidation() throws Exception {
        String json = """
                {
                    "upstreamMsgId": "TXN-2025-007",
                    "recipient": "+919876543210",
                    "channel": "INVALID_CHANNEL",
                    "payload": {
                        "transactionId": "TXN-007",
                        "message": "Bad channel"
                    }
                }
                """;

        Set<ValidationMessage> errors = schema.validate(mapper.readTree(json));
        assertThat(errors).isNotEmpty();
    }
}
