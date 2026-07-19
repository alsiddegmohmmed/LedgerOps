package com.ledgerops.payment.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.merchant.api.MerchantReference;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;

class PaymentLifecycleTests {

    private static final Set<Transition> ALLOWED_TRANSITIONS = Set.of(
            new Transition(PaymentStatus.CREATED, PaymentStatus.VALIDATING),
            new Transition(PaymentStatus.VALIDATING, PaymentStatus.APPROVED),
            new Transition(PaymentStatus.VALIDATING, PaymentStatus.RISK_REVIEW),
            new Transition(PaymentStatus.VALIDATING, PaymentStatus.REJECTED),
            new Transition(PaymentStatus.RISK_REVIEW, PaymentStatus.APPROVED),
            new Transition(PaymentStatus.RISK_REVIEW, PaymentStatus.REJECTED),
            new Transition(PaymentStatus.APPROVED, PaymentStatus.PROCESSING),
            new Transition(PaymentStatus.PROCESSING, PaymentStatus.COMPLETED),
            new Transition(PaymentStatus.PROCESSING, PaymentStatus.FAILED),
            new Transition(PaymentStatus.COMPLETED, PaymentStatus.REVERSED)
    );

    @Test
    void containsExactlyTheApprovedPaymentStatuses() {
        assertArrayEquals(
                new PaymentStatus[]{
                    PaymentStatus.CREATED,
                    PaymentStatus.VALIDATING,
                    PaymentStatus.RISK_REVIEW,
                    PaymentStatus.APPROVED,
                    PaymentStatus.REJECTED,
                    PaymentStatus.PROCESSING,
                    PaymentStatus.COMPLETED,
                    PaymentStatus.FAILED,
                    PaymentStatus.REVERSED
                },
                PaymentStatus.values()
        );
    }

    @Test
    void permitsEveryApprovedTransitionAndRejectsEveryOtherExposedTransition() {
        for (PaymentStatus source : PaymentStatus.values()) {
            for (PaymentStatus target : PaymentStatus.values()) {
                if (target == PaymentStatus.CREATED) {
                    continue;
                }

                Payment payment = paymentIn(source);
                Transition transition = new Transition(source, target);

                if (ALLOWED_TRANSITIONS.contains(transition)) {
                    Payment transitioned = transitionTo(payment, target);

                    assertEquals(target, transitioned.status(), transition.toString());
                    assertEquals(source, payment.status(), "Transitions must not mutate the source Payment");
                } else {
                    IllegalStateException exception = assertThrows(
                            IllegalStateException.class,
                            () -> transitionTo(payment, target),
                            transition.toString()
                    );

                    assertEquals(
                            "Payment cannot transition from " + source + " to " + target,
                            exception.getMessage()
                    );
                    assertEquals(source, payment.status(), "Rejected transitions must preserve state");
                }
            }
        }
    }

    @Test
    void rejectedFailedAndReversedAreTerminal() {
        for (PaymentStatus terminal : Set.of(
                PaymentStatus.REJECTED,
                PaymentStatus.FAILED,
                PaymentStatus.REVERSED
        )) {
            Payment payment = paymentIn(terminal);

            for (PaymentStatus target : PaymentStatus.values()) {
                if (target == PaymentStatus.CREATED) {
                    continue;
                }

                assertThrows(
                        IllegalStateException.class,
                        () -> transitionTo(payment, target),
                        terminal + " must be terminal"
                );
            }
        }
    }

    private Payment transitionTo(Payment payment, PaymentStatus target) {
        return switch (target) {
            case VALIDATING -> payment.startValidation();
            case RISK_REVIEW -> payment.requestRiskReview();
            case APPROVED -> payment.approve();
            case REJECTED -> payment.reject();
            case PROCESSING -> payment.startProcessing();
            case COMPLETED -> payment.complete();
            case FAILED -> payment.fail();
            case REVERSED -> payment.reverse();
            case CREATED -> throw new IllegalArgumentException(
                    "Payment creation is not a lifecycle transition"
            );
        };
    }

    private Payment paymentIn(PaymentStatus status) {
        Payment created = newPayment();

        return switch (status) {
            case CREATED -> created;
            case VALIDATING -> created.startValidation();
            case RISK_REVIEW -> created.startValidation().requestRiskReview();
            case APPROVED -> created.startValidation().approve();
            case REJECTED -> created.startValidation().reject();
            case PROCESSING -> created.startValidation().approve().startProcessing();
            case COMPLETED -> created.startValidation().approve().startProcessing().complete();
            case FAILED -> created.startValidation().approve().startProcessing().fail();
            case REVERSED -> created.startValidation().approve().startProcessing().complete().reverse();
        };
    }

    private Payment newPayment() {
        return Payment.create(
                PaymentId.from(UUID.fromString("32142591-bca0-4907-884a-fefcc2fa86ae")),
                MerchantReference.from(
                        UUID.fromString("9d492ac1-cf66-41cc-a82d-96f8a6d22e93"),
                        UUID.fromString("94f012db-f9b8-40e8-8188-f9625fca531a")
                ),
                CustomerId.from(UUID.fromString("fe389759-5cbc-490a-a355-835c7082dca2")),
                Money.of(new BigDecimal("125.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("payment-request-1001")
        );
    }

    private record Transition(PaymentStatus source, PaymentStatus target) {
    }
}
