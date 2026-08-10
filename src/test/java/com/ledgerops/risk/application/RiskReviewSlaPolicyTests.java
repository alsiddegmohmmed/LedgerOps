package com.ledgerops.risk.application;

import com.ledgerops.risk.api.RiskReviewId;
import com.ledgerops.risk.domain.RiskReview;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskReviewSlaPolicyTests {
    private static final Instant CREATED = Instant.parse("2026-08-10T10:00:00Z");

    @Test
    void initialPolicyUsesTwentyFourHoursAndAssignmentDoesNotResetTheDueTime() {
        Clock createdClock = Clock.fixed(CREATED, ZoneOffset.UTC);
        ConfiguredRiskReviewSlaPolicy policy = new ConfiguredRiskReviewSlaPolicy(
                1, Duration.ofHours(24));
        Instant dueAt = policy.dueAt(createdClock.instant(), 7);

        assertEquals(Duration.ofHours(24), policy.durationFor(0));
        assertEquals(Duration.ofHours(24), policy.durationFor(7));
        assertEquals(CREATED.plus(Duration.ofHours(24)), dueAt);

        RiskReview review = RiskReview.create(
                RiskReviewId.newId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                7, policy.version(), createdClock.instant(), dueAt);
        RiskReview reassigned = review.assign(UUID.randomUUID(), 3);

        assertEquals(dueAt, reassigned.dueAt());
        assertFalse(reassigned.snapshot().overdueAt(
                Clock.fixed(CREATED.plus(Duration.ofHours(23).plusMinutes(59)), ZoneOffset.UTC).instant()));
        assertTrue(reassigned.snapshot().overdueAt(
                Clock.fixed(dueAt, ZoneOffset.UTC).instant()));
    }
}
