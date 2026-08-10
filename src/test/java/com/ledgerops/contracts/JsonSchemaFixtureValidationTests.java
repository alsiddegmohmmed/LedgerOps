package com.ledgerops.contracts;

import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaFixtureValidationTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012);
    private static final Map<String, String> FIXTURES = Map.ofEntries(
            Map.entry("MessageEnvelope.schema.json", "message-envelope-valid.json"),
            Map.entry("SubmitPaymentToProvider.schema.json", "submit-payment-valid.json"),
            Map.entry("SubmitReversalToProvider.schema.json", "submit-reversal-valid.json"),
            Map.entry("ProviderResultObserved.schema.json", "provider-result-observed-valid.json"),
            Map.entry("ProviderReversalResultObserved.schema.json",
                    "provider-reversal-result-observed-valid.json"),
            Map.entry("PaymentCompleted.schema.json", "payment-completed-valid.json"),
            Map.entry("PaymentFailed.schema.json", "payment-failed-valid.json"),
            Map.entry("ReversalRequested.schema.json", "reversal-requested-valid.json"),
            Map.entry("ReversalProcessingStarted.schema.json",
                    "reversal-processing-started-valid.json"),
            Map.entry("ReversalFailed.schema.json", "reversal-failed-valid.json"),
            Map.entry("ReversalCompleted.schema.json", "reversal-completed-valid.json"),
            Map.entry("PaymentSubmissionRetryRequested.schema.json",
                    "payment-submission-retry-requested-valid.json")
    );

    @Test
    void everyEventSchemaAcceptsItsValidFixtureAndRejectsAMissingRequiredField()
            throws Exception {
        Path root = Path.of("packages/event-contracts/v1");
        for (var contract : FIXTURES.entrySet()) {
            verify(root.resolve(contract.getKey()),
                    root.resolve("fixtures").resolve(contract.getValue()));
        }
    }

    @Test
    void providerWebhookSchemaAcceptsItsFixtureAndRejectsAMissingRequiredField()
            throws Exception {
        Path root = Path.of("packages/provider-contracts/v1");
        verify(root.resolve("ProviderWebhook.schema.json"),
                root.resolve("fixtures/provider-webhook-success-valid.json"));
    }

    private void verify(Path schemaPath, Path fixturePath) throws Exception {
        JsonNode schemaDocument = JSON.readTree(schemaPath.toFile());
        JsonNode valid = JSON.readTree(fixturePath.toFile());
        var schema = SCHEMAS.getSchema(schemaDocument.toString());

        assertTrue(schema.validate(valid.toString(), InputFormat.JSON).isEmpty(),
                schemaPath.toString());

        ObjectNode invalid = (ObjectNode) valid.deepCopy();
        String firstRequired = schemaDocument.required("required").get(0).asString();
        invalid.remove(firstRequired);
        assertFalse(schema.validate(invalid.toString(), InputFormat.JSON).isEmpty(),
                schemaPath.toString());
    }
}
