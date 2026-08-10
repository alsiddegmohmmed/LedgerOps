package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
interface PaymentLifecycleEventAppender {

    void append(
            Payment before,
            Payment after,
            long aggregateVersion,
            String actorSource,
            String reasonCode,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt
    );

    static PaymentLifecycleEventAppender noOp() {
        return (before, after, aggregateVersion, actorSource, reasonCode,
                correlationId, causationId, occurredAt) -> { };
    }
}
