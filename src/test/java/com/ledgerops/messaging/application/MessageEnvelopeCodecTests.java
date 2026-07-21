package com.ledgerops.messaging.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageEnvelopeCodecTests {

    private final MessageEnvelopeCodec codec = new MessageEnvelopeCodec();

    @Test
    void envelopeUsesExactlyTheApprovedVersionOneFields() {
        UUID messageId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();
        String encoded = codec.encode(new OutboxClaim(
                UUID.randomUUID(), messageId, "SubmitPaymentToProvider", 1,
                paymentId, tenantId, "ledgerops.provider.commands.v1",
                paymentId.toString(), "{\"attemptId\":\"" + UUID.randomUUID() + "\"}",
                correlationId, causationId, Instant.parse("2026-07-21T12:00:00Z"),
                1, UUID.randomUUID()
        ));

        MessageEnvelope decoded = codec.decode(encoded);

        assertEquals(messageId, decoded.messageId());
        assertEquals(paymentId, decoded.aggregateId());
        assertEquals(tenantId, decoded.tenantId());
        assertEquals(correlationId, decoded.correlationId());
        assertEquals(causationId, decoded.causationId());
        assertEquals(messageId, codec.trustworthyMessageId(encoded));
    }

    @Test
    void extensionAtEnvelopeLevelIsRejected() {
        String envelope = """
                {"messageId":"00000000-0000-0000-0000-000000000001",\
                "messageType":"SubmitPaymentToProvider","schemaVersion":1,\
                "aggregateId":"00000000-0000-0000-0000-000000000002",\
                "tenantId":"00000000-0000-0000-0000-000000000003",\
                "correlationId":"00000000-0000-0000-0000-000000000004",\
                "causationId":"00000000-0000-0000-0000-000000000005",\
                "occurredAt":"2026-07-21T12:00:00Z","payload":{},"extra":true}
                """;

        assertThrows(InvalidEnvelopeException.class, () -> codec.decode(envelope));
    }
}
