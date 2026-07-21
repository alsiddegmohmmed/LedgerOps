package com.ledgerops.provider.application;

public class ProviderCallDeferredException extends RuntimeException {
    private final String reasonCode;

    public ProviderCallDeferredException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
