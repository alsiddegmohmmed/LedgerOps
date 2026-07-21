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

class ProviderResultObservedContractTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path ROOT = Path.of("packages/event-contracts/v1");

    @Test
    void schemaAndFixturePreserveTheExactVersionOneProviderEvidenceBoundary()
            throws Exception {
        JsonNode schema = JSON.readTree(ROOT.resolve(
                "ProviderResultObserved.schema.json").toFile());
        JsonNode fixture = JSON.readTree(ROOT.resolve(
                "fixtures/provider-result-observed-valid.json").toFile());
        Set<String> required = new HashSet<>();
        schema.required("required").forEach(value -> required.add(value.asString()));

        assertEquals(Set.of("providerEvidenceId", "providerResultId", "attemptId",
                "paymentId", "providerId", "providerIdempotencyKey",
                "providerResultCategory", "retryDisposition", "evidenceOrigin",
                "observedAt"), required);
        required.forEach(field -> assertTrue(fixture.has(field), field));
        UUID.fromString(fixture.required("providerEvidenceId").asString());
        UUID.fromString(fixture.required("providerResultId").asString());
        UUID.fromString(fixture.required("attemptId").asString());
        UUID paymentId = UUID.fromString(fixture.required("paymentId").asString());
        assertEquals("SIMULATOR", fixture.required("providerId").asString());
        assertEquals("payment:" + paymentId,
                fixture.required("providerIdempotencyKey").asString());
        Instant.parse(fixture.required("observedAt").asString());
        assertEquals(Set.of("SUCCESS", "ACCEPTED", "DECLINED", "PENDING",
                        "TEMPORARY_FAILURE", "PERMANENT_FAILURE", "UNKNOWN"),
                values(schema.required("properties").required(
                        "providerResultCategory").required("enum")));
        assertEquals(Set.of("SAFE_TO_RESUBMIT", "STATUS_RECOVERY_REQUIRED",
                        "NOT_RETRYABLE"),
                values(schema.required("properties").required(
                        "retryDisposition").required("enum")));
    }

    private Set<String> values(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
