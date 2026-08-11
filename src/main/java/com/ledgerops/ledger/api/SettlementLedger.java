package com.ledgerops.ledger.api;

import java.util.Optional;
import java.util.UUID;

public interface SettlementLedger {

    Optional<LedgerPostingEvidence> findBySettlementPostingSource(
            UUID tenantId,
            UUID settlementPostingId
    );

    LedgerPostingEvidence postSettlement(SettlementPostingRequest request);
}
