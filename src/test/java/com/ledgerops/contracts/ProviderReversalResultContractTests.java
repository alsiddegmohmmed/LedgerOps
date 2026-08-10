package com.ledgerops.contracts;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ProviderReversalResultContractTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path ROOT = Path.of("packages/event-contracts/v1");

    @Test
    void reversalResultUsesTheOriginatingPaymentPartitionAndTypedReversalIdentity()
            throws Exception {
        JsonNode fixture = JSON.readTree(ROOT.resolve(
                "fixtures/provider-reversal-result-observed-valid.json").toFile());
        UUID paymentId = UUID.fromString(fixture.required("paymentId").asString());
        UUID reversalId = UUID.fromString(fixture.required("reversalId").asString());

        assertEquals("REVERSAL", fixture.required("operationType").asString());
        assertEquals("reversal:" + reversalId,
                fixture.required("providerIdempotencyKey").asString());
        assertEquals("SIMULATOR", fixture.required("providerId").asString());
        assertNotEquals(paymentId, reversalId);
    }
}
