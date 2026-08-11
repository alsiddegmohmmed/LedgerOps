package com.ledgerops.reconciliation.application;

import com.ledgerops.reconciliation.domain.SettlementValidationReasonCode;

public final class SettlementStructuralException extends RuntimeException {
    private final SettlementValidationReasonCode reasonCode;

    public SettlementStructuralException(SettlementValidationReasonCode reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public SettlementValidationReasonCode reasonCode() {
        return reasonCode;
    }
}
