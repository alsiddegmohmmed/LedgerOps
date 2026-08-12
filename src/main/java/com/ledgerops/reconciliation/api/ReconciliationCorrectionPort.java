package com.ledgerops.reconciliation.api;

import java.util.UUID;

/**
 * Published Reconciliation boundary for validating and locking a controlled
 * settlement correction target.
 */
public interface ReconciliationCorrectionPort {

    SettlementCorrectionEligibility lockAndCheck(
            UUID tenantId,
            UUID discrepancyId,
            UUID settlementPostingId,
            UUID originalLedgerTransactionId
    );
}
