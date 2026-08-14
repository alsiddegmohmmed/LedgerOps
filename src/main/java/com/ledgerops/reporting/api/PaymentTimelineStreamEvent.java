package com.ledgerops.reporting.api;

import java.util.Objects;

/** A persisted, derived event in the Payment-timeline Tenant stream. */
public record PaymentTimelineStreamEvent(
        long eventId,
        PaymentTimelineEntry entry
) {

    public PaymentTimelineStreamEvent {
        if (eventId <= 0) {
            throw new IllegalArgumentException("Projection event ID must be positive");
        }
        Objects.requireNonNull(entry, "Projection event entry must not be null");
    }
}
