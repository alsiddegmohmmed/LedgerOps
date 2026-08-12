package com.ledgerops.ledger.application;

import com.ledgerops.ledger.domain.LedgerSourceType;
import com.ledgerops.ledger.domain.LedgerTransaction;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionStore {

    void insert(LedgerTransaction transaction);

    Optional<LedgerTransaction> findBySource(
            UUID tenantId,
            LedgerSourceType sourceType,
            UUID sourceId
    );

    Optional<LedgerTransaction> findById(UUID tenantId, UUID transactionId);

    Optional<LedgerTransaction> lockById(UUID tenantId, UUID transactionId);

    Optional<LedgerTransaction> findCompensationForTarget(
            UUID tenantId,
            UUID targetTransactionId
    );
}
