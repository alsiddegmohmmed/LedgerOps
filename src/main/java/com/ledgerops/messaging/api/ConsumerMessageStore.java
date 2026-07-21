package com.ledgerops.messaging.api;

import java.util.UUID;

public interface ConsumerMessageStore {

    InboxResult recordProcessed(IncomingMessage message);

    ConsumerFailureResult recordFailure(
            IncomingMessage message,
            String rawEnvelope,
            String payloadHash,
            String topic,
            int partition,
            long offset,
            String reasonCode,
            String safeSummary,
            UUID correlationId,
            boolean immediatelyDead
    );

    void recordTransportDeadLetter(
            String consumerName,
            String topic,
            int partition,
            long offset,
            String rawRecordHash,
            byte[] boundedSafeBytes,
            String reasonCode,
            String safeSummary,
            UUID correlationId
    );
}
