package com.ledgerops.casework.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CaseOperationalSummaryContractTests {

    @Test
    void rejectsResolvedOrClosedCasesAtThePublishedBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new CaseOperationalSummary(
                UUID.randomUUID(), UUID.randomUUID(), "RISK_REVIEW", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "RESOLVED",
                Instant.parse("2026-08-13T00:00:00Z")));
    }
}
