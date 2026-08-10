package com.ledgerops.messaging.api;

import java.util.UUID;

/**
 * Public boundary for decoding the canonical messaging envelope.
 */
public interface MessageEnvelopeDecoder {

    UUID trustworthyMessageId(String rawEnvelope);

    MessageEnvelopeView decodeForConsumer(String rawEnvelope);
}
