package com.ledgerops.ledger.application;

import com.ledgerops.ledger.domain.LedgerTransaction;

public interface LedgerTransactionStore {

    void insert(LedgerTransaction transaction);
}
