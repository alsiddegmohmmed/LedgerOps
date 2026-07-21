package com.ledgerops.contracts;

import com.ledgerops.messaging.application.InvalidEnvelopeException;
import com.ledgerops.messaging.application.MessageEnvelopeCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageEnvelopeContractTests {

    private static final Path ROOT = Path.of("packages/event-contracts/v1");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FIELDS = Set.of(
            "messageId", "messageType", "schemaVersion", "aggregateId", "tenantId",
            "correlationId", "causationId", "occurredAt", "payload"
    );

    @Test
    void schemaClosesTheVersionOneTopLevelEnvelope() throws Exception {
        JsonNode schema = JSON.readTree(ROOT.resolve("MessageEnvelope.schema.json").toFile());

        assertFalse(schema.required("additionalProperties").asBoolean());
        assertEquals(FIELDS, texts(schema.required("required")));
        assertEquals(FIELDS, new HashSet<>(schema.required("properties").propertyNames()));
        assertEquals(1, schema.required("properties").required("schemaVersion")
                .required("const").intValue());
    }

    @Test
    void canonicalConsumerAcceptsTheValidFixtureAndRejectsTopLevelExtensions() throws Exception {
        MessageEnvelopeCodec codec = new MessageEnvelopeCodec();
        String valid = Files.readString(ROOT.resolve("fixtures/message-envelope-valid.json"));
        String invalid = Files.readString(
                ROOT.resolve("fixtures/message-envelope-extension-invalid.json")
        );

        assertEquals(1, codec.decode(valid).schemaVersion());
        assertThrows(InvalidEnvelopeException.class, () -> codec.decode(invalid));
    }

    private Set<String> texts(JsonNode values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.asString()));
        return result;
    }
}
