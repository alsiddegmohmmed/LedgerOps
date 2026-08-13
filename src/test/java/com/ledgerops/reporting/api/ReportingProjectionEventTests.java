package com.ledgerops.reporting.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReportingProjectionEventTests {

    @Test
    void exposesTheApprovedCurrentAffectedCodeInStableWireOrder() {
        ReportingProjectionEvent event = new ReportingProjectionEvent(
                18428,
                UUID.randomUUID(),
                3,
                Set.of(ReportingProjectionAffected.OPERATIONAL_SUMMARY),
                null,
                Instant.parse("2026-08-13T03:25:10Z"));

        assertThat(event.affectedInWireOrder())
                .containsExactly(ReportingProjectionAffected.OPERATIONAL_SUMMARY);
    }

    @Test
    void resyncReplayCannotContainEvents() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ReportingProjectionEventReplay(
                        List.of(new ReportingProjectionEvent(
                                1, UUID.randomUUID(), 1,
                                Set.of(ReportingProjectionAffected.OPERATIONAL_SUMMARY), null,
                                Instant.parse("2026-08-13T03:25:10Z"))), true));
    }
}
