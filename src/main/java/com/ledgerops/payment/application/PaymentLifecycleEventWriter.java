package com.ledgerops.payment.application;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.payment.domain.Payment;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
class PaymentLifecycleEventWriter implements PaymentLifecycleEventAppender {

    private final MessageOutbox outbox;
    private final Clock clock;

    PaymentLifecycleEventWriter(MessageOutbox outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void append(
            Payment before,
            Payment after,
            long aggregateVersion,
            String actorSource,
            String reasonCode,
            UUID correlationId,
            UUID causationId,
            Instant occurredAt
    ) {
        outbox.appendOrGet(PaymentLifecycleEventFactory.draft(
                before,
                after,
                aggregateVersion,
                actorSource,
                reasonCode,
                correlationId,
                causationId,
                occurredAt.truncatedTo(ChronoUnit.MICROS)));
    }

    void appendAutomated(
            Payment before,
            Payment after,
            long aggregateVersion,
            String reasonCode
    ) {
        append(
                before,
                after,
                aggregateVersion,
                "AUTOMATED",
                reasonCode,
                PaymentLifecycleEventFactory.deterministicCorrelation(after, aggregateVersion),
                PaymentLifecycleEventFactory.deterministicCausation(after, aggregateVersion),
                clock.instant());
    }
}
