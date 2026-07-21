package com.ledgerops.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

class SubmitPaymentToProviderContractTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path ROOT = Path.of("packages/event-contracts/v1");

    @Test
    void schemaDefinesTheExactVersionOnePayload() throws Exception {
        JsonNode schema = JSON.readTree(ROOT.resolve(
                "SubmitPaymentToProvider.schema.json"
        ).toFile());

        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.get("additionalProperties").asBoolean());
        assertEquals(
                Set.of(
                        "attemptId", "paymentId", "attemptSequence", "providerId",
                        "providerIdempotencyKey", "amount", "currency",
                        "paymentMethodCategory", "requestIntentHash"
                ),
                nodeTextSet(schema.get("required"))
        );
        assertEquals(nodeTextSet(schema.get("required")),
                nameSet(schema.get("properties").propertyNames()));
    }

    @Test
    void validFixturePassesAndInvalidFixtureFailsTheContract() throws Exception {
        JsonNode valid = JSON.readTree(ROOT.resolve(
                "fixtures/submit-payment-valid.json"
        ).toFile());
        JsonNode invalid = JSON.readTree(ROOT.resolve(
                "fixtures/submit-payment-invalid.json"
        ).toFile());
        JsonNode jpy = JSON.readTree(ROOT.resolve(
                "fixtures/submit-payment-jpy-valid.json"
        ).toFile());
        JsonNode extended = JSON.readTree(ROOT.resolve(
                "fixtures/submit-payment-extension-valid.json"
        ).toFile());

        assertTrue(isValid(valid));
        assertTrue(isValid(jpy));
        assertTrue(isValid(extended));
        assertFalse(isValid(invalid));
    }

    @Test
    void nonCanonicalLeadingZeroAmountIsRejected() throws Exception {
        JsonNode payload = JSON.readTree(ROOT.resolve(
                "fixtures/submit-payment-valid.json"
        ).toFile()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) payload).put("amount", "012.30");

        assertFalse(isValid(payload));
    }

    @Test
    void wrongJsonFieldTypesAreRejected() throws Exception {
        JsonNode payload = JSON.readTree(ROOT.resolve(
                "fixtures/submit-payment-valid.json"
        ).toFile()).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) payload).put("amount", 12);

        assertFalse(isValid(payload));
    }

    private boolean isValid(JsonNode payload) {
        try {
            UUID.fromString(payload.required("attemptId").asText());
            UUID paymentId = UUID.fromString(payload.required("paymentId").asText());
            return payload.required("attemptId").isTextual()
                    && payload.required("paymentId").isTextual()
                    && payload.required("attemptSequence").isIntegralNumber()
                    && payload.required("attemptSequence").asInt() >= 1
                    && payload.required("providerId").isTextual()
                    && payload.required("providerId").asText().equals("SIMULATOR")
                    && payload.required("providerIdempotencyKey").isTextual()
                    && payload.required("providerIdempotencyKey").asText().equals(
                            "payment:" + paymentId.toString().toLowerCase()
                    )
                    && payload.required("amount").isTextual()
                    && Pattern.matches(
                            "^(0|[1-9][0-9]*)(\\.[0-9]+)?$",
                            payload.required("amount").asText()
                    )
                    && payload.required("currency").isTextual()
                    && Pattern.matches("^[A-Z]{3}$", payload.required("currency").asText())
                    && payload.required("paymentMethodCategory").isTextual()
                    && !payload.required("paymentMethodCategory").asText().isBlank()
                    && payload.required("requestIntentHash").isTextual()
                    && Pattern.matches(
                            "^[0-9a-f]{64}$",
                            payload.required("requestIntentHash").asText()
                    );
        } catch (Exception exception) {
            return false;
        }
    }

    private Set<String> nodeTextSet(JsonNode nodes) {
        Set<String> values = new HashSet<>();
        nodes.forEach(node -> values.add(node.asText()));
        return values;
    }

    private Set<String> nameSet(Collection<String> names) {
        return new HashSet<>(names);
    }

}
