package com.ledgerops.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationRunTests {

    private static final Instant CREATED_AT = Instant.parse("2026-08-11T08:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-08-11T08:01:00Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-08-11T08:02:00Z");

    @Test
    void followsTheApprovedSuccessfulRunLifecycle() {
        ReconciliationRun run = queued();

        run.start(STARTED_AT);
        run.complete(new ReconciliationRunCounts(10, 0, 0), FINISHED_AT);

        assertThat(run.status()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run.startedAt()).contains(STARTED_AT);
        assertThat(run.terminalAt()).contains(FINISHED_AT);
        assertThat(run.counts()).contains(new ReconciliationRunCounts(10, 0, 0));
        assertThat(run.failureReason()).isEmpty();
    }

    @Test
    void completedWithDiscrepanciesIsDifferentFromSuccessfulCompletion() {
        ReconciliationRun run = queued();
        run.start(STARTED_AT);

        run.complete(new ReconciliationRunCounts(8, 1, 1), FINISHED_AT);

        assertThat(run.status()).isEqualTo(ReconciliationRunStatus.COMPLETED_WITH_DISCREPANCIES);
    }

    @Test
    void recordsFailureAndAllowsCancellationOnlyBeforeTerminalState() {
        ReconciliationRun failed = queued();
        failed.start(STARTED_AT);
        failed.fail("Snapshot source was unavailable", FINISHED_AT);

        assertThat(failed.status()).isEqualTo(ReconciliationRunStatus.FAILED);
        assertThat(failed.failureReason()).contains("Snapshot source was unavailable");

        ReconciliationRun cancelled = queued();
        cancelled.cancel(STARTED_AT);
        assertThat(cancelled.status()).isEqualTo(ReconciliationRunStatus.CANCELLED);
    }

    @Test
    void rejectsInvalidTransitionsAndCounts() {
        ReconciliationRun run = queued();

        assertThatThrownBy(() -> run.complete(new ReconciliationRunCounts(1, 0, 0), FINISHED_AT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ReconciliationRunCounts(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        run.start(STARTED_AT);
        run.complete(new ReconciliationRunCounts(1, 0, 0), FINISHED_AT);
        assertThatThrownBy(() -> run.cancel(Instant.parse("2026-08-11T08:03:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keepsTheApprovedClosedVocabularies() {
        assertThat(ReconciliationRunStatus.values()).containsExactly(
                ReconciliationRunStatus.QUEUED,
                ReconciliationRunStatus.RUNNING,
                ReconciliationRunStatus.COMPLETED,
                ReconciliationRunStatus.COMPLETED_WITH_DISCREPANCIES,
                ReconciliationRunStatus.FAILED,
                ReconciliationRunStatus.CANCELLED);
        assertThat(ReconciliationStatus.values()).containsExactly(
                ReconciliationStatus.NOT_APPLICABLE,
                ReconciliationStatus.AWAITING_BATCH,
                ReconciliationStatus.PENDING,
                ReconciliationStatus.MATCHED,
                ReconciliationStatus.DISCREPANCY);
        assertThat(ReconciliationDiscrepancyCategory.values()).containsExactly(
                ReconciliationDiscrepancyCategory.MISSING_INTERNAL_PAYMENT,
                ReconciliationDiscrepancyCategory.MISSING_PROVIDER_RECORD,
                ReconciliationDiscrepancyCategory.MISSING_INTERNAL_REVERSAL,
                ReconciliationDiscrepancyCategory.MISSING_PROVIDER_REVERSAL,
                ReconciliationDiscrepancyCategory.AMOUNT_MISMATCH,
                ReconciliationDiscrepancyCategory.CURRENCY_MISMATCH,
                ReconciliationDiscrepancyCategory.STATUS_MISMATCH,
                ReconciliationDiscrepancyCategory.DUPLICATE_PROVIDER_RECORD,
                ReconciliationDiscrepancyCategory.DUPLICATE_INTERNAL_REFERENCE,
                ReconciliationDiscrepancyCategory.LEDGER_TRANSACTION_MISSING,
                ReconciliationDiscrepancyCategory.LEDGER_AMOUNT_MISMATCH,
                ReconciliationDiscrepancyCategory.SETTLEMENT_DATE_MISMATCH,
                ReconciliationDiscrepancyCategory.UNRESOLVED_PROVIDER_REFERENCE,
                ReconciliationDiscrepancyCategory.INVALID_PROVIDER_RECORD,
                ReconciliationDiscrepancyCategory.REVERSAL_WITHOUT_PAYMENT_SETTLEMENT);
    }

    private ReconciliationRun queued() {
        return ReconciliationRun.queued(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "release-0.3-reconciliation-v1",
                Instant.parse("2026-08-11T07:59:00Z"), CREATED_AT);
    }
}
