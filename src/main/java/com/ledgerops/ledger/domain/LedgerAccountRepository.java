package com.ledgerops.ledger.domain;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository {

    void insert(LedgerAccount account);

    Optional<LedgerAccount> findById(
            UUID tenantId,
            LedgerAccountId accountId
    );

    Optional<LedgerAccount> findByIdentity(
            UUID tenantId,
            AccountCode accountCode,
            Currency currency
    );
}
