package com.ledgerops.ledger.api;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record LedgerPostingEvidence(
        UUID transactionId,
        UUID tenantId,
        String sourceType,
        UUID sourceId,
        Currency currency,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        List<LedgerPostingEntryEvidence> entries,
        Optional<UUID> compensatesTransactionId
) {

    public LedgerPostingEvidence {
        Objects.requireNonNull(transactionId, "Ledger transaction ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(sourceType, "Ledger source type must not be null");
        Objects.requireNonNull(sourceId, "Ledger source ID must not be null");
        Objects.requireNonNull(currency, "Ledger currency must not be null");
        Objects.requireNonNull(totalDebits, "Ledger debit total must not be null");
        Objects.requireNonNull(totalCredits, "Ledger credit total must not be null");
        entries = List.copyOf(Objects.requireNonNull(entries, "Ledger entries must not be null"));
        Objects.requireNonNull(
                compensatesTransactionId,
                "Ledger compensation reference must not be null"
        );
    }
}
