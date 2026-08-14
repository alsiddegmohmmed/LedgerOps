package com.ledgerops.reporting.infrastructure;

import com.ledgerops.reporting.api.PaymentTimelineEntry;
import com.ledgerops.reporting.api.PaymentTimelineStreamEvent;
import com.ledgerops.reporting.api.PaymentTimelineStreamReplay;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentTimelineStreamTests {

    @Test
    void resyncReplayCannotCarryEvents() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentTimelineStreamReplay(
                List.of(new PaymentTimelineStreamEvent(1, fact())), true));
    }

    @Test
    void eventIdsMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentTimelineStreamEvent(0, fact()));
    }

    @Test
    void resyncFactoryProducesAnEmptyExplicitResyncResult() {
        PaymentTimelineStreamReplay replay = PaymentTimelineStreamReplay.resync();

        assertEquals(List.of(), replay.events());
        assertEquals(true, replay.resyncRequired());
    }

    @Test
    void cursorImmediatelyBeforeOldestRetainedEventCanReplay() {
        org.junit.jupiter.api.Assertions.assertFalse(
                PaymentTimelineProjectionStore.cursorUnavailable(2, 3));
    }

    @Test
    void cursorWithAGapBeforeOldestRetainedEventRequiresResync() {
        org.junit.jupiter.api.Assertions.assertTrue(
                PaymentTimelineProjectionStore.cursorUnavailable(1, 3));
    }

    private static PaymentTimelineEntry fact() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        return new PaymentTimelineEntry(
                UUID.randomUUID(), tenantId, paymentId, UUID.randomUUID(),
                "PAYMENT", "PaymentLifecycleChanged", paymentId,
                Instant.parse("2026-08-12T10:00:00Z"), "AUTOMATED", "COMPLETED",
                null, null, "Payment completed");
    }
}
