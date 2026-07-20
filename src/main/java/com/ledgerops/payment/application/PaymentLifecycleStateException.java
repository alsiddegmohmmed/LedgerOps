package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;

import java.util.Objects;

public final class PaymentLifecycleStateException extends RuntimeException {

    private final PaymentId paymentId;
    private final PaymentStatus requiredStatus;
    private final PaymentStatus actualStatus;

    public PaymentLifecycleStateException(
            PaymentId paymentId,
            PaymentStatus requiredStatus,
            PaymentStatus actualStatus
    ) {
        super(
                "Payment " + paymentId.value() + " must be " + requiredStatus
                        + " but is " + actualStatus
        );
        this.paymentId = Objects.requireNonNull(paymentId, "Payment ID must not be null");
        this.requiredStatus = Objects.requireNonNull(
                requiredStatus,
                "Required Payment status must not be null"
        );
        this.actualStatus = Objects.requireNonNull(
                actualStatus,
                "Actual Payment status must not be null"
        );
    }

    public PaymentId paymentId() {
        return paymentId;
    }

    public PaymentStatus requiredStatus() {
        return requiredStatus;
    }

    public PaymentStatus actualStatus() {
        return actualStatus;
    }
}
