package com.ledgerops.reconciliation.domain;

public final class SettlementRecordValidationException extends RuntimeException {
    private final SettlementValidationReasonCode reasonCode;

    public SettlementRecordValidationException(
            SettlementValidationReasonCode reasonCode,
            String message
    ) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public SettlementValidationReasonCode reasonCode() {
        return reasonCode;
    }
}
