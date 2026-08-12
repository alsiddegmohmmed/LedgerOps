package com.ledgerops.ledger.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Published Ledger boundary for the one approved Release 0.3 correction kind.
 */
public interface SettlementCorrectionLedger {

    Optional<LedgerPostingEvidence> findByCorrectionSource(
            UUID tenantId,
            UUID correctionId
    );

    Optional<LedgerPostingEvidence> findCompensationForTarget(
            UUID tenantId,
            UUID originalTransactionId
    );

    LedgerPostingEvidence postCompensation(SettlementCorrectionRequest request);
}
