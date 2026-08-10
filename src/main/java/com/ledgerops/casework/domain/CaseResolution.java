package com.ledgerops.casework.domain;

public enum CaseResolution {
    RISK_APPROVE,
    RISK_REJECT,
    PROVIDER_ERROR,
    INTERNAL_PROCESSING_ERROR,
    DUPLICATE_EXTERNAL_RECORD,
    EXPECTED_TIMING_DIFFERENCE,
    APPROVED_CORRECTION,
    FALSE_POSITIVE;

    public boolean allowedFor(CaseSourceCategory source) {
        return switch (source) {
            case RISK_REVIEW -> this == RISK_APPROVE || this == RISK_REJECT;
            case RECONCILIATION_DISCREPANCY -> this == PROVIDER_ERROR
                    || this == INTERNAL_PROCESSING_ERROR
                    || this == DUPLICATE_EXTERNAL_RECORD
                    || this == EXPECTED_TIMING_DIFFERENCE
                    || this == APPROVED_CORRECTION
                    || this == FALSE_POSITIVE;
        };
    }
}
