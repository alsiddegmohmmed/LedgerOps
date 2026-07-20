package com.ledgerops.ledger.api;

import java.util.Optional;
import java.util.UUID;

public interface PaymentSuccessLedger {

    Optional<LedgerPostingEvidence> findByPaymentSource(
            UUID tenantId,
            UUID paymentId
    );

    LedgerPostingEvidence postPaymentSuccess(PaymentSuccessPostingRequest request);
}
