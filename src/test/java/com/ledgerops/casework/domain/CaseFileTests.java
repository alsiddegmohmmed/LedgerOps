package com.ledgerops.casework.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CaseFileTests {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID PAYMENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    @Test
    void enforcesApprovedCaseLifecycle() {
        CaseFile file = CaseFile.open(UUID.randomUUID(), TENANT, CaseSourceCategory.RISK_REVIEW,
                SOURCE, PAYMENT, CaseSeverity.HIGH, NOW.plusSeconds(3600));
        file = file.assign(ACTOR, ACTOR, "Take ownership", NOW);
        file = file.transition(CaseStatus.INVESTIGATING, ACTOR, "Start investigation", NOW);
        file = file.resolve(CaseResolution.RISK_APPROVE, "Payment evidence is acceptable", true, ACTOR, NOW);
        file = file.close(ACTOR, "Investigation complete", NOW);

        assertEquals(CaseStatus.CLOSED, file.status());
        assertEquals(CaseResolution.RISK_APPROVE, file.resolution());
        assertEquals(4, file.history().size());
    }

    @Test
    void riskCaseCannotResolveWithoutPaymentEffect() {
        CaseFile file = CaseFile.open(UUID.randomUUID(), TENANT, CaseSourceCategory.RISK_REVIEW,
                SOURCE, PAYMENT, CaseSeverity.HIGH, NOW.plusSeconds(3600))
                .transition(CaseStatus.INVESTIGATING, ACTOR, "Start", NOW);
        assertThrows(CaseResolutionException.class,
                () -> file.resolve(CaseResolution.RISK_REJECT, "Reject", false, ACTOR, NOW));
    }

    @Test
    void reopenRequiresReasonAndPreservesHistory() {
        CaseFile file = CaseFile.open(UUID.randomUUID(), TENANT, CaseSourceCategory.RISK_REVIEW,
                SOURCE, PAYMENT, CaseSeverity.HIGH, NOW.plusSeconds(3600))
                .transition(CaseStatus.INVESTIGATING, ACTOR, "Start", NOW)
                .resolve(CaseResolution.RISK_REJECT, "Reject", true, ACTOR, NOW)
                .close(ACTOR, "Close", NOW);
        assertThrows(IllegalArgumentException.class,
                () -> file.transition(CaseStatus.REOPENED, ACTOR, "", NOW));
        CaseFile reopened = file.transition(CaseStatus.REOPENED, ACTOR, "New evidence", NOW);
        assertEquals(CaseStatus.REOPENED, reopened.status());
        assertEquals(4, reopened.history().size());
        assertEquals(CaseResolution.RISK_REJECT, reopened.resolution());
    }

    @Test
    void resolutionCatalogIsSourceSpecific() {
        assertTrue(CaseResolution.RISK_APPROVE.allowedFor(CaseSourceCategory.RISK_REVIEW));
        assertFalse(CaseResolution.RISK_APPROVE.allowedFor(CaseSourceCategory.RECONCILIATION_DISCREPANCY));
        assertFalse(CaseResolution.APPROVED_CORRECTION.allowedFor(CaseSourceCategory.RISK_REVIEW));
    }
}
