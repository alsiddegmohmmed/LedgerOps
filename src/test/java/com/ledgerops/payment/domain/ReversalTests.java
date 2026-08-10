package com.ledgerops.payment.domain;

import com.ledgerops.merchant.api.MerchantReference;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReversalTests {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-11T10:00:00Z");
    private static final Instant PROCESSING_AT = REQUESTED_AT.plusSeconds(1);
    private static final Instant FAILED_AT = REQUESTED_AT.plusSeconds(2);
    private static final Instant COMPLETED_AT = REQUESTED_AT.plusSeconds(3);

    @Test
    void containsExactlyTheApprovedStatuses() {
        assertArrayEquals(
                new ReversalStatus[]{
                    ReversalStatus.REQUESTED,
                    ReversalStatus.PROCESSING,
                    ReversalStatus.FAILED,
                    ReversalStatus.COMPLETED
                },
                ReversalStatus.values()
        );
    }

    @Test
    void copiesTheCompletedPaymentIdentityAndFullAmount() {
        Payment payment = completedPayment();
        UUID actorId = UUID.randomUUID();

        Reversal reversal = Reversal.request(
                ReversalId.newId(), payment, actorId, "Customer requested reversal", REQUESTED_AT);

        assertEquals(payment.tenantId(), reversal.tenantId());
        assertEquals(payment.id(), reversal.paymentId());
        assertEquals(payment.merchantReference().value(), reversal.merchantId());
        assertEquals(payment.amount(), reversal.amount());
        assertEquals(actorId, reversal.requestedBy());
        assertEquals("Customer requested reversal", reversal.requestReason());
        assertEquals(ReversalStatus.REQUESTED, reversal.status());
    }

    @Test
    void onlyACompletedPaymentCanBeRequestedForReversal() {
        Payment processing = paymentIn(PaymentStatus.PROCESSING);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> Reversal.request(
                        ReversalId.newId(), processing, UUID.randomUUID(), "reason", REQUESTED_AT)
        );

        assertEquals("Only a COMPLETED Payment can be reversed", exception.getMessage());
    }

    @Test
    void supportsInitialProcessingFailureSafeRetryAndCompletion() {
        Reversal requested = requestedReversal();
        Reversal processing = requested.startProcessing(PROCESSING_AT);
        Reversal failed = processing.fail("SAFE_TO_RESUBMIT", FAILED_AT);
        Reversal retried = failed.startSafeRetry(COMPLETED_AT.minusSeconds(1));
        Reversal completed = retried.complete(COMPLETED_AT);

        assertEquals(ReversalStatus.REQUESTED, requested.status());
        assertEquals(ReversalStatus.PROCESSING, processing.status());
        assertEquals(ReversalStatus.FAILED, failed.status());
        assertEquals(ReversalStatus.PROCESSING, retried.status());
        assertEquals(ReversalStatus.COMPLETED, completed.status());
        assertEquals("SAFE_TO_RESUBMIT", completed.failureCategory());
        assertEquals(COMPLETED_AT, completed.completedAt());
    }

    @Test
    void rejectsEveryTransitionOutsideTheApprovedLifecycle() {
        for (ReversalStatus source : ReversalStatus.values()) {
            Reversal reversal = reversalIn(source);
            for (ReversalStatus target : ReversalStatus.values()) {
                if (isAllowed(source, target)) {
                    continue;
                }

                assertThrows(
                        IllegalStateException.class,
                        () -> transitionTo(reversal, target),
                        source + " -> " + target + " must be rejected"
                );
            }
        }
    }

    @Test
    void requiresRequestReasonAndPositiveAmount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Reversal.request(
                        ReversalId.newId(), completedPayment(), UUID.randomUUID(), " ", REQUESTED_AT)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> Reversal.rehydrate(
                        ReversalId.newId(),
                        UUID.randomUUID(),
                        PaymentId.newId(),
                        UUID.randomUUID(),
                        Money.of(BigDecimal.ZERO, Currency.getInstance("SAR")),
                        ReversalStatus.REQUESTED,
                        UUID.randomUUID(),
                        "reason",
                        REQUESTED_AT,
                        null,
                        null,
                        null,
                        null,
                        0)
        );
    }

    private Reversal requestedReversal() {
        return Reversal.request(
                ReversalId.newId(),
                completedPayment(),
                UUID.randomUUID(),
                "Customer requested reversal",
                REQUESTED_AT
        );
    }

    private Reversal reversalIn(ReversalStatus status) {
        Reversal requested = requestedReversal();
        return switch (status) {
            case REQUESTED -> requested;
            case PROCESSING -> requested.startProcessing(PROCESSING_AT);
            case FAILED -> requested.startProcessing(PROCESSING_AT)
                    .fail("SAFE_TO_RESUBMIT", FAILED_AT);
            case COMPLETED -> requested.startProcessing(PROCESSING_AT)
                    .complete(COMPLETED_AT);
        };
    }

    private Reversal transitionTo(Reversal reversal, ReversalStatus target) {
        return switch (target) {
            case REQUESTED -> throw new IllegalStateException("Request is creation, not a transition");
            case PROCESSING -> reversal.status() == ReversalStatus.FAILED
                    ? reversal.startSafeRetry(PROCESSING_AT)
                    : reversal.startProcessing(PROCESSING_AT);
            case FAILED -> reversal.fail("SAFE_TO_RESUBMIT", FAILED_AT);
            case COMPLETED -> reversal.complete(COMPLETED_AT);
        };
    }

    private boolean isAllowed(ReversalStatus source, ReversalStatus target) {
        return (source == ReversalStatus.REQUESTED && target == ReversalStatus.PROCESSING)
                || (source == ReversalStatus.PROCESSING
                && (target == ReversalStatus.FAILED || target == ReversalStatus.COMPLETED))
                || (source == ReversalStatus.FAILED && target == ReversalStatus.PROCESSING);
    }

    private Payment completedPayment() {
        return paymentIn(PaymentStatus.COMPLETED);
    }

    private Payment paymentIn(PaymentStatus status) {
        Payment created = Payment.create(
                PaymentId.newId(),
                MerchantReference.from(UUID.randomUUID(), UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("payment-reversal-test")
        );

        return switch (status) {
            case CREATED -> created;
            case VALIDATING -> created.startValidation();
            case RISK_REVIEW -> created.startValidation().requestRiskReview();
            case APPROVED -> created.startValidation().approve();
            case PROCESSING -> created.startValidation().approve().startProcessing();
            case COMPLETED -> created.startValidation().approve().startProcessing().complete();
            case REJECTED -> created.startValidation().reject();
            case FAILED -> created.startValidation().approve().startProcessing().fail();
            case REVERSED -> completedPayment().reverse();
        };
    }
}
