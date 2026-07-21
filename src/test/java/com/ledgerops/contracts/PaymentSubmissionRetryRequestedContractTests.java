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

class PaymentSubmissionRetryRequestedContractTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void validFixtureMatchesTheClosedVersionOneContract() throws Exception {
        JsonNode schema = json.readTree(Path.of(
                "packages/event-contracts/v1/PaymentSubmissionRetryRequested.schema.json")
                .toFile());
        JsonNode fixture = json.readTree(Path.of(
                "packages/event-contracts/v1/fixtures/payment-submission-retry-requested-valid.json")
                .toFile());
        Set<String> required = new HashSet<>();
        schema.required("required").forEach(value -> required.add(value.asString()));

        assertEquals(Set.of("retryRequestId", "paymentId", "previousAttemptId",
                "providerEvidenceId", "providerId", "requestedAt"), required);
        required.forEach(field -> fixture.required(field));
        UUID.fromString(fixture.required("retryRequestId").asString());
        UUID.fromString(fixture.required("paymentId").asString());
        UUID.fromString(fixture.required("previousAttemptId").asString());
        UUID.fromString(fixture.required("providerEvidenceId").asString());
        assertEquals("SIMULATOR", fixture.required("providerId").asString());
        Instant.parse(fixture.required("requestedAt").asString());
    }
}
