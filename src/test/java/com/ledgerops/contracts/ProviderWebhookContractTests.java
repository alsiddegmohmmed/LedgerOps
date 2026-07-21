package com.ledgerops.contracts;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderWebhookContractTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path ROOT = Path.of("packages/provider-contracts/v1");

    @Test
    void schemaAndFixtureDefineTheVersionOneWebhookWithoutTenantInput() throws Exception {
        JsonNode schema = JSON.readTree(ROOT.resolve("ProviderWebhook.schema.json").toFile());
        JsonNode fixture = JSON.readTree(ROOT.resolve(
                "fixtures/provider-webhook-success-valid.json").toFile());
        Set<String> required = values(schema.required("required"));

        assertEquals(Set.of("providerEventId", "providerResultId",
                "providerIdempotencyKey", "providerResultCategory", "providerOccurredAt"),
                required);
        assertFalse(schema.required("properties").has("tenantId"));
        assertFalse(schema.required("properties").has("paymentId"));
        assertFalse(schema.required("properties").has("attemptId"));
        assertTrue(schema.required("additionalProperties").asBoolean());
        assertTrue(valid(fixture));
        assertEquals(Set.of("SUCCESS", "ACCEPTED", "DECLINED", "PENDING",
                        "TEMPORARY_FAILURE", "PERMANENT_FAILURE", "UNKNOWN"),
                values(schema.required("properties").required(
                        "providerResultCategory").required("enum")));
    }

    @Test
    void wrongTypesAndBodySuppliedTenantIdentityAreNotAcceptedAsIdentity() throws Exception {
        JsonNode fixture = JSON.readTree(ROOT.resolve(
                "fixtures/provider-webhook-success-valid.json").toFile());
        var wrongType = (tools.jackson.databind.node.ObjectNode) fixture.deepCopy();
        wrongType.put("providerEventId", 42);
        assertFalse(valid(wrongType));

        var extended = (tools.jackson.databind.node.ObjectNode) fixture.deepCopy();
        extended.put("tenantId", UUID.randomUUID().toString());
        assertTrue(valid(extended), "Unknown fields remain compatible contract extensions");
    }

    private boolean valid(JsonNode value) {
        try {
            if (!value.isObject()
                    || !value.required("providerEventId").isTextual()
                    || !value.required("providerResultId").isTextual()
                    || !value.required("providerIdempotencyKey").isTextual()
                    || !value.required("providerResultCategory").isTextual()
                    || !value.required("providerOccurredAt").isTextual()) {
                return false;
            }
            UUID.fromString(value.required("providerEventId").asString());
            UUID.fromString(value.required("providerResultId").asString());
            Instant.parse(value.required("providerOccurredAt").asString());
            return value.required("providerIdempotencyKey").asString().matches(
                    "^payment:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
                    && Set.of("SUCCESS", "ACCEPTED", "DECLINED", "PENDING",
                    "TEMPORARY_FAILURE", "PERMANENT_FAILURE", "UNKNOWN").contains(
                    value.required("providerResultCategory").asString());
        } catch (Exception exception) {
            return false;
        }
    }

    private Set<String> values(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
