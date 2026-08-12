package com.ledgerops.casework.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorrectionRequestTests {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID DISCREPANCY = UUID.randomUUID();
    private static final UUID INSTRUCTION = UUID.randomUUID();
    private static final UUID ORIGINAL_TRANSACTION = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-11T08:00:00Z");

    @Test
    void followsTheApprovedLifecycleAndSupportsRetryAfterFailure() {
        CorrectionRequest request = request();

        CorrectionRequest processing = request.beginProcessing(REQUESTED_AT.plusSeconds(1));
        CorrectionRequest failed = processing.fail("Ledger was temporarily unavailable",
                REQUESTED_AT.plusSeconds(2));
        CorrectionRequest retried = failed.beginProcessing(REQUESTED_AT.plusSeconds(3));
        UUID compensation = UUID.randomUUID();
        CorrectionRequest completed = retried.complete(compensation, REQUESTED_AT.plusSeconds(4));

        assertEquals(CorrectionRequestStatus.REQUESTED, request.status());
        assertEquals(CorrectionRequestStatus.FAILED, failed.status());
        assertEquals("Ledger was temporarily unavailable", failed.failureReason());
        assertEquals(CorrectionRequestStatus.PROCESSING, retried.status());
        assertEquals(CorrectionRequestStatus.COMPLETED, completed.status());
        assertEquals(compensation, completed.compensationLedgerTransactionId());
    }

    @Test
    void completedRequestRequiresACompensationLedgerTransaction() {
        CorrectionRequest request = request().beginProcessing(REQUESTED_AT.plusSeconds(1));

        assertThrows(NullPointerException.class,
                () -> request.complete(null, REQUESTED_AT.plusSeconds(2)));
        assertNull(request.compensationLedgerTransactionId());
    }

    @Test
    void terminalAndInvalidTransitionsAreRejected() {
        CorrectionRequest requested = request();
        assertThrows(CorrectionRequestStateException.class,
                () -> requested.complete(UUID.randomUUID(), REQUESTED_AT.plusSeconds(1)));

        CorrectionRequest completed = requested
                .beginProcessing(REQUESTED_AT.plusSeconds(1))
                .complete(UUID.randomUUID(), REQUESTED_AT.plusSeconds(2));
        assertThrows(CorrectionRequestStateException.class,
                () -> completed.beginProcessing(REQUESTED_AT.plusSeconds(3)));
    }

    @Test
    void failureRequiresAReason() {
        CorrectionRequest processing = request().beginProcessing(REQUESTED_AT.plusSeconds(1));

        assertThrows(IllegalArgumentException.class,
                () -> processing.fail(" ", REQUESTED_AT.plusSeconds(2)));
    }

    private CorrectionRequest request() {
        return CorrectionRequest.request(
                UUID.randomUUID(),
                TENANT,
                CASE_ID,
                DISCREPANCY,
                INSTRUCTION,
                ORIGINAL_TRANSACTION,
                ACTOR,
                "Compensate invalidated settlement adjustment",
                REQUESTED_AT
        );
    }
}
