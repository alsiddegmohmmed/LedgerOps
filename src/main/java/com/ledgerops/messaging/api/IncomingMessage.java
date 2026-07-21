package com.ledgerops.messaging.api;

import java.util.Objects;
import java.util.UUID;

public record IncomingMessage(
        String consumerName,
        UUID messageId,
        UUID tenantId,
        String messageType
) {
    public IncomingMessage {
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("Consumer name must not be blank");
        }
        Objects.requireNonNull(messageId, "Message ID must not be null");
        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("Message type must not be blank");
        }
    }
}
