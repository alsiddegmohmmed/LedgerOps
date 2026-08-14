package com.ledgerops.reporting.api;

import java.util.List;
import java.util.Objects;

/**
 * Result of replaying a Tenant's persisted Payment-timeline events.
 *
 * <p>A resync result carries no replay events. The caller must load a fresh
 * Tenant snapshot before opening a new stream.</p>
 */
public record PaymentTimelineStreamReplay(
        List<PaymentTimelineStreamEvent> events,
        boolean resyncRequired
) {

    public PaymentTimelineStreamReplay {
        Objects.requireNonNull(events, "Replay events must not be null");
        events = List.copyOf(events);
        if (resyncRequired && !events.isEmpty()) {
            throw new IllegalArgumentException(
                    "A resync result must not contain replay events");
        }
    }

    public static PaymentTimelineStreamReplay resync() {
        return new PaymentTimelineStreamReplay(List.of(), true);
    }
}
