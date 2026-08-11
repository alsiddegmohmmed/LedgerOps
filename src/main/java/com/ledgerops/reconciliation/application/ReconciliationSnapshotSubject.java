package com.ledgerops.reconciliation.application;

import com.ledgerops.ledger.api.LedgerSettlementEvidence;
import com.ledgerops.payment.api.PaymentReconciliationSubject;
import com.ledgerops.provider.api.ProviderEvidence;

import java.util.Objects;

public record ReconciliationSnapshotSubject(
        PaymentReconciliationSubject subject,
        ProviderEvidence providerEvidence,
        LedgerSettlementEvidence ledgerEvidence
) {

    public ReconciliationSnapshotSubject {
        Objects.requireNonNull(subject, "Reconciliation subject must not be null");
    }
}
