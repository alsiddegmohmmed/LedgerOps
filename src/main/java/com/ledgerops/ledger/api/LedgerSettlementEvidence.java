package com.ledgerops.ledger.api;

import java.time.Instant;
import java.util.Objects;

public record LedgerSettlementEvidence(
        LedgerSettlementSource source,
        Instant postedAt,
        LedgerPostingEvidence posting
) {

    public LedgerSettlementEvidence {
        Objects.requireNonNull(source, "Settlement source must not be null");
        Objects.requireNonNull(postedAt, "Posted-at time must not be null");
        Objects.requireNonNull(posting, "Ledger posting must not be null");
    }
}
