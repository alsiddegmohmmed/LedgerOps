package com.ledgerops.payment.domain;

import com.ledgerops.merchant.api.MerchantReference;

import java.util.Objects;
import java.util.UUID;

public final class Payment {

    private final PaymentId id;
    private final MerchantReference merchantReference;
    private final CustomerId customerId;
    private final Money amount;
    private final PaymentMethodCategory paymentMethodCategory;
    private final IdempotencyKey idempotencyKey;
    private final PaymentStatus status;

    private Payment(
            PaymentId id,
            MerchantReference merchantReference,
            CustomerId customerId,
            Money amount,
            PaymentMethodCategory paymentMethodCategory,
            IdempotencyKey idempotencyKey,
            PaymentStatus status
    ) {
        this.id = Objects.requireNonNull(id, "Payment ID must not be null");
        this.merchantReference = Objects.requireNonNull(
                merchantReference,
                "Merchant reference must not be null"
        );
        this.customerId = Objects.requireNonNull(customerId, "Customer ID must not be null");
        this.amount = requirePositiveAmount(amount);
        this.paymentMethodCategory = Objects.requireNonNull(
                paymentMethodCategory,
                "Payment method category must not be null"
        );
        this.idempotencyKey = Objects.requireNonNull(
                idempotencyKey,
                "Idempotency key must not be null"
        );
        this.status = Objects.requireNonNull(status, "Payment status must not be null");
    }

    public static Payment create(
            PaymentId id,
            MerchantReference merchantReference,
            CustomerId customerId,
            Money amount,
            PaymentMethodCategory paymentMethodCategory,
            IdempotencyKey idempotencyKey
    ) {
        return new Payment(
                id,
                merchantReference,
                customerId,
                amount,
                paymentMethodCategory,
                idempotencyKey,
                PaymentStatus.CREATED
        );
    }

    public static Payment rehydrate(
            PaymentId id,
            MerchantReference merchantReference,
            CustomerId customerId,
            Money amount,
            PaymentMethodCategory paymentMethodCategory,
            IdempotencyKey idempotencyKey,
            PaymentStatus status
    ) {
        return new Payment(
                id,
                merchantReference,
                customerId,
                amount,
                paymentMethodCategory,
                idempotencyKey,
                status
        );
    }

    public Payment startValidation() {
        return transitionTo(PaymentStatus.VALIDATING, PaymentStatus.CREATED);
    }

    public Payment requestRiskReview() {
        return transitionTo(PaymentStatus.RISK_REVIEW, PaymentStatus.VALIDATING);
    }

    public Payment approve() {
        return transitionTo(
                PaymentStatus.APPROVED,
                PaymentStatus.VALIDATING,
                PaymentStatus.RISK_REVIEW
        );
    }

    public Payment reject() {
        return transitionTo(
                PaymentStatus.REJECTED,
                PaymentStatus.VALIDATING,
                PaymentStatus.RISK_REVIEW
        );
    }

    public Payment startProcessing() {
        return transitionTo(PaymentStatus.PROCESSING, PaymentStatus.APPROVED);
    }

    public Payment complete() {
        return transitionTo(PaymentStatus.COMPLETED, PaymentStatus.PROCESSING);
    }

    public Payment fail() {
        return transitionTo(PaymentStatus.FAILED, PaymentStatus.PROCESSING);
    }

    public Payment reverse() {
        return transitionTo(PaymentStatus.REVERSED, PaymentStatus.COMPLETED);
    }

    public PaymentId id() {
        return id;
    }

    public UUID tenantId() {
        return merchantReference.tenantId();
    }

    public MerchantReference merchantReference() {
        return merchantReference;
    }

    public CustomerId customerId() {
        return customerId;
    }

    public Money amount() {
        return amount;
    }

    public PaymentMethodCategory paymentMethodCategory() {
        return paymentMethodCategory;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }

    public PaymentStatus status() {
        return status;
    }

    private Payment transitionTo(
            PaymentStatus targetStatus,
            PaymentStatus... allowedCurrentStatuses
    ) {
        for (PaymentStatus allowedStatus : allowedCurrentStatuses) {
            if (status == allowedStatus) {
                return withStatus(targetStatus);
            }
        }

        throw new IllegalStateException(
                "Payment cannot transition from " + status + " to " + targetStatus
        );
    }

    private Payment withStatus(PaymentStatus newStatus) {
        return new Payment(
                id,
                merchantReference,
                customerId,
                amount,
                paymentMethodCategory,
                idempotencyKey,
                newStatus
        );
    }

    private static Money requirePositiveAmount(Money amount) {
        Objects.requireNonNull(amount, "Payment amount must not be null");

        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }

        return amount;
    }
}
