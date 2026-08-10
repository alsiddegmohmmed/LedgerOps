package com.ledgerops.ledger.api;

import java.util.Optional;
import java.util.UUID;

public interface ReversalLedger {

    Optional<LedgerPostingEvidence> findByReversalSource(
            UUID tenantId,
            UUID reversalId
    );

    LedgerPostingEvidence postCompensation(
            ReversalCompensationPostingRequest request
    );
}
