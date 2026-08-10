package com.ledgerops.provider.api;

public enum ProviderSettlementMode {
    EXACT,
    MISSING,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    STATUS_MISMATCH,
    DUPLICATE_RECORD,
    DATE_MISMATCH
}
