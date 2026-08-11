package com.ledgerops.ledger.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface LedgerSettlementEvidenceQuery {

    Map<LedgerSettlementSource, LedgerSettlementEvidence> findBySources(
            UUID tenantId,
            Collection<LedgerSettlementSource> sources
    );
}
