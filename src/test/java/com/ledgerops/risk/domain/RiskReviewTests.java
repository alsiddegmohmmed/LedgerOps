package com.ledgerops.risk.domain;

import com.ledgerops.risk.api.RiskReviewDecision;
import com.ledgerops.risk.api.RiskReviewId;
import com.ledgerops.risk.api.RiskReviewStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RiskReviewTests {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PAYMENT = UUID.randomUUID();
    private static final UUID EVALUATION = UUID.randomUUID();
    private static final UUID ANALYST = UUID.randomUUID();
    private static final Instant CREATED = Instant.parse("2026-08-10T10:00:00Z");

    @Test
    void followsUnassignedAssignedDecidedLifecycle() {
        RiskReview review = RiskReview.create(RiskReviewId.newId(), TENANT, PAYMENT, EVALUATION,
                80, 3, CREATED, CREATED.plusSeconds(3600));

        RiskReview assigned = review.assign(ANALYST, 90);
        RiskReview decided = assigned.decide(ANALYST, RiskReviewDecision.APPROVE,
                "Evidence supports approval", null, CREATED.plusSeconds(5));

        assertEquals(RiskReviewStatus.UNASSIGNED, review.status());
        assertEquals(RiskReviewStatus.ASSIGNED, assigned.status());
        assertEquals(RiskReviewStatus.DECIDED, decided.status());
        assertEquals(ANALYST, decided.assignedAnalystId());
        assertEquals(RiskReviewDecision.APPROVE, decided.decision());
    }

    @Test
    void escalationRequiresCaseAndIsFinal() {
        UUID caseId = UUID.randomUUID();
        RiskReview assigned = RiskReview.create(RiskReviewId.newId(), TENANT, PAYMENT, EVALUATION,
                0, 1, CREATED, CREATED.plusSeconds(3600)).assign(ANALYST, 0);

        RiskReview escalated = assigned.decide(ANALYST, RiskReviewDecision.ESCALATE,
                "Needs investigation", caseId, CREATED.plusSeconds(5));

        assertEquals(RiskReviewStatus.ESCALATED, escalated.status());
        assertEquals(caseId, escalated.caseId());
        assertThrows(RiskReviewStateException.class,
                () -> escalated.assign(UUID.randomUUID(), 0));
        assertThrows(RiskReviewStateException.class,
                () -> escalated.decide(ANALYST, RiskReviewDecision.REJECT, "Different", null, CREATED));
    }

    @Test
    void onlyAssignedAnalystMayDecide() {
        RiskReview assigned = RiskReview.create(RiskReviewId.newId(), TENANT, PAYMENT, EVALUATION,
                0, 1, CREATED, CREATED.plusSeconds(3600)).assign(ANALYST, 0);
        assertThrows(RiskReviewAuthorizationException.class,
                () -> assigned.decide(UUID.randomUUID(), RiskReviewDecision.REJECT,
                        "Not assigned", null, CREATED.plusSeconds(5)));
    }

    @Test
    void dueTimeIsEvaluatedAtBoundary() {
        RiskReview review = RiskReview.create(RiskReviewId.newId(), TENANT, PAYMENT, EVALUATION,
                0, 1, CREATED, CREATED.plusSeconds(3600));
        assertFalse(review.snapshot().overdueAt(CREATED.plusSeconds(3599)));
        assertTrue(review.snapshot().overdueAt(CREATED.plusSeconds(3600)));
    }
}
