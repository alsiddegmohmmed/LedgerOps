package com.ledgerops.payment.application;

import java.util.Objects;

public final class PaymentReferenceUnavailableException extends RuntimeException {

    private final PaymentReferenceType referenceType;
    private final String reason;

    public PaymentReferenceUnavailableException(
            PaymentReferenceType referenceType,
            String reason
    ) {
        super(referenceType + " cannot accept new payment activity: " + reason);
        this.referenceType = Objects.requireNonNull(
                referenceType,
                "Payment reference type must not be null"
        );
        this.reason = Objects.requireNonNull(reason, "Payment reference reason must not be null");
    }

    public PaymentReferenceType referenceType() {
        return referenceType;
    }

    public String reason() {
        return reason;
    }
}
