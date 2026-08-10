package com.ledgerops.reporting.api;

import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.payment.api.PaymentAttemptSnapshot;
import com.ledgerops.payment.api.PaymentNoteSnapshot;
import com.ledgerops.payment.api.PaymentDetailsSnapshot;
import com.ledgerops.provider.api.ProviderEvidence;
import com.ledgerops.provider.api.ProviderPaymentOperations;
import com.ledgerops.risk.api.RiskPaymentSnapshot;

import java.util.List;

public record PaymentOperationalDetail(
        PaymentDetailsSnapshot payment,
        RiskPaymentSnapshot risk,
        List<ProviderEvidence> providerEvidence,
        LedgerPostingEvidence ledgerPosting,
        String reconciliationStatus,
        List<PaymentTimelineEntry> timeline,
        List<PaymentNoteSnapshot> notes,
        List<PaymentAttemptSnapshot> attempts,
        ProviderPaymentOperations providerOperations
) {

    public PaymentOperationalDetail {
        providerEvidence = List.copyOf(providerEvidence);
        timeline = List.copyOf(timeline);
        notes = List.copyOf(notes);
        attempts = List.copyOf(attempts);
    }
}
