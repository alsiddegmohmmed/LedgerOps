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
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentLifecycleContractTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path ROOT = Path.of("packages/event-contracts/v1");

    @Test
    void completedContractHasTheExactRequiredVersionOneFields() throws Exception {
        JsonNode schema = schema("PaymentCompleted.schema.json");
        JsonNode fixture = fixture("payment-completed-valid.json");

        assertEquals(Set.of(
                "paymentId", "attemptId", "providerEvidenceId",
                "ledgerTransactionId", "completedAt"
        ), required(schema));
        required(schema).forEach(field -> assertTrue(fixture.has(field), field));
        UUID.fromString(fixture.required("paymentId").asString());
        UUID.fromString(fixture.required("attemptId").asString());
        UUID.fromString(fixture.required("providerEvidenceId").asString());
        UUID.fromString(fixture.required("ledgerTransactionId").asString());
        Instant.parse(fixture.required("completedAt").asString());
    }

    @Test
    void failedContractAllowsOnlyTheApprovedDefinitiveCategories() throws Exception {
        JsonNode schema = schema("PaymentFailed.schema.json");
        JsonNode fixture = fixture("payment-failed-valid.json");

        assertEquals(Set.of(
                "paymentId", "attemptId", "providerEvidenceId",
                "finalCategory", "failedAt"
        ), required(schema));
        assertEquals(Set.of("DECLINED", "PERMANENT_FAILURE"), values(
                schema.required("properties").required("finalCategory").required("enum")
        ));
        required(schema).forEach(field -> assertTrue(fixture.has(field), field));
        Instant.parse(fixture.required("failedAt").asString());
    }

    private JsonNode schema(String name) throws Exception {
        return JSON.readTree(ROOT.resolve(name).toFile());
    }

    private JsonNode fixture(String name) throws Exception {
        return JSON.readTree(ROOT.resolve("fixtures").resolve(name).toFile());
    }

    private Set<String> required(JsonNode schema) {
        return values(schema.required("required"));
    }

    private Set<String> values(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
