package com.ledgerops.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Reversal {

    private final ReversalId id;
    private final UUID tenantId;
    private final PaymentId paymentId;
    private final UUID merchantId;
    private final Money amount;
    private final ReversalStatus status;
    private final UUID requestedBy;
    private final String requestReason;
    private final Instant requestedAt;
    private final Instant processingAt;
    private final Instant failedAt;
    private final Instant completedAt;
    private final String failureCategory;
    private final long version;

    private Reversal(
            ReversalId id,
            UUID tenantId,
            PaymentId paymentId,
            UUID merchantId,
            Money amount,
            ReversalStatus status,
            UUID requestedBy,
            String requestReason,
            Instant requestedAt,
            Instant processingAt,
            Instant failedAt,
            Instant completedAt,
            String failureCategory,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "Reversal ID must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        this.paymentId = Objects.requireNonNull(paymentId, "Payment ID must not be null");
        this.merchantId = Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        this.amount = requirePositiveAmount(amount);
        this.status = Objects.requireNonNull(status, "Reversal status must not be null");
        this.requestedBy = Objects.requireNonNull(requestedBy, "Request actor must not be null");
        this.requestReason = requireReason(requestReason);
        this.requestedAt = Objects.requireNonNull(requestedAt, "Request time must not be null");
        this.processingAt = processingAt;
        this.failedAt = failedAt;
        this.completedAt = completedAt;
        this.failureCategory = normalizeFailureCategory(failureCategory);
        if (version < 0) {
            throw new IllegalArgumentException("Reversal version must not be negative");
        }
        this.version = version;
        validateStateShape();
    }

    public static Reversal request(
            ReversalId id,
            Payment payment,
            UUID requestedBy,
            String reason,
            Instant requestedAt
    ) {
        Objects.requireNonNull(payment, "Payment must not be null");
        if (payment.status() != PaymentStatus.COMPLETED) {
            throw new IllegalStateException("Only a COMPLETED Payment can be reversed");
        }
        return new Reversal(
                id,
                payment.tenantId(),
                payment.id(),
                payment.merchantReference().value(),
                payment.amount(),
                ReversalStatus.REQUESTED,
                requestedBy,
                reason,
                requestedAt,
                null,
                null,
                null,
                null,
                0
        );
    }

    public static Reversal rehydrate(
            ReversalId id,
            UUID tenantId,
            PaymentId paymentId,
            UUID merchantId,
            Money amount,
            ReversalStatus status,
            UUID requestedBy,
            String requestReason,
            Instant requestedAt,
            Instant processingAt,
            Instant failedAt,
            Instant completedAt,
            String failureCategory,
            long version
    ) {
        return new Reversal(
                id,
                tenantId,
                paymentId,
                merchantId,
                amount,
                status,
                requestedBy,
                requestReason,
                requestedAt,
                processingAt,
                failedAt,
                completedAt,
                failureCategory,
                version
        );
    }

    public Reversal startProcessing(Instant now) {
        if (status != ReversalStatus.REQUESTED) {
            throw invalidTransition(ReversalStatus.PROCESSING);
        }
        return copy(
                ReversalStatus.PROCESSING,
                Objects.requireNonNull(now, "Processing time must not be null"),
                failedAt,
                null,
                failureCategory
        );
    }

    public Reversal startSafeRetry(Instant now) {
        if (status != ReversalStatus.FAILED) {
            throw invalidTransition(ReversalStatus.PROCESSING);
        }
        return copy(
                ReversalStatus.PROCESSING,
                Objects.requireNonNull(now, "Retry processing time must not be null"),
                failedAt,
                null,
                failureCategory
        );
    }

    public Reversal fail(String category, Instant now) {
        if (status != ReversalStatus.PROCESSING) {
            throw invalidTransition(ReversalStatus.FAILED);
        }
        return copy(
                ReversalStatus.FAILED,
                processingAt,
                Objects.requireNonNull(now, "Failure time must not be null"),
                null,
                category
        );
    }

    public Reversal complete(Instant now) {
        if (status != ReversalStatus.PROCESSING) {
            throw invalidTransition(ReversalStatus.COMPLETED);
        }
        return copy(
                ReversalStatus.COMPLETED,
                processingAt,
                failedAt,
                Objects.requireNonNull(now, "Completion time must not be null"),
                failureCategory
        );
    }

    public ReversalId id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public PaymentId paymentId() {
        return paymentId;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public Money amount() {
        return amount;
    }

    public ReversalStatus status() {
        return status;
    }

    public UUID requestedBy() {
        return requestedBy;
    }

    public String requestReason() {
        return requestReason;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Instant processingAt() {
        return processingAt;
    }

    public Instant failedAt() {
        return failedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public String failureCategory() {
        return failureCategory;
    }

    public long version() {
        return version;
    }

    private Reversal copy(
            ReversalStatus nextStatus,
            Instant nextProcessingAt,
            Instant nextFailedAt,
            Instant nextCompletedAt,
            String nextFailureCategory
    ) {
        return new Reversal(
                id,
                tenantId,
                paymentId,
                merchantId,
                amount,
                nextStatus,
                requestedBy,
                requestReason,
                requestedAt,
                nextProcessingAt,
                nextFailedAt,
                nextCompletedAt,
                nextFailureCategory,
                version
        );
    }

    private IllegalStateException invalidTransition(ReversalStatus target) {
        return new IllegalStateException(
                "Reversal cannot transition from " + status + " to " + target
        );
    }

    private void validateStateShape() {
        if (processingAt != null && processingAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException("Processing time must not precede request time");
        }
        if (failedAt != null && failedAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException("Failure time must not precede request time");
        }
        if (completedAt != null && completedAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException("Completion time must not precede request time");
        }

        switch (status) {
            case REQUESTED -> requireShape(
                    processingAt == null && failedAt == null && completedAt == null
                            && failureCategory == null,
                    "REQUESTED Reversal cannot contain processing, failure, or completion facts"
            );
            case PROCESSING -> requireShape(
                    processingAt != null && completedAt == null
                            && (failedAt == null) == (failureCategory == null),
                    "PROCESSING Reversal requires processing facts and no completion fact"
            );
            case FAILED -> requireShape(
                    processingAt != null && failedAt != null && completedAt == null
                            && failureCategory != null,
                    "FAILED Reversal requires processing and failure facts"
            );
            case COMPLETED -> requireShape(
                    processingAt != null && completedAt != null
                            && (failedAt == null) == (failureCategory == null),
                    "COMPLETED Reversal requires processing and completion facts"
            );
        }
    }

    private static void requireShape(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Money requirePositiveAmount(Money amount) {
        Objects.requireNonNull(amount, "Reversal amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Reversal amount must be positive");
        }
        return amount;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reversal request reason must not be blank");
        }
        return reason.trim();
    }

    private static String normalizeFailureCategory(String category) {
        if (category == null) {
            return null;
        }
        if (category.isBlank()) {
            throw new IllegalArgumentException("Reversal failure category must not be blank");
        }
        return category.trim();
    }
}
