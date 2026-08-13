package com.ledgerops.reporting.api;

import java.util.List;
import java.util.Objects;

/** Persisted Reporting events after a supplied cursor, or a resync instruction. */
public record ReportingProjectionEventReplay(
        List<ReportingProjectionEvent> events,
        boolean resyncRequired
) {

    public ReportingProjectionEventReplay {
        Objects.requireNonNull(events, "Replay events must not be null");
        events = List.copyOf(events);
        if (resyncRequired && !events.isEmpty()) {
            throw new IllegalArgumentException("A resync result must not contain replay events");
        }
    }

    public static ReportingProjectionEventReplay resync() {
        return new ReportingProjectionEventReplay(List.of(), true);
    }
}
