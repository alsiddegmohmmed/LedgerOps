package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.ledger.api.LedgerPostingEntryEvidence;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.PaymentSuccessLedger;
import com.ledgerops.ledger.api.PaymentSuccessPostingRequest;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class CompletePaymentAfterProviderSuccessTests {

    private static final Currency SAR = Currency.getInstance("SAR");

    @Test
    void completesProcessingPaymentUsingTheExactPostingContract() {
        Payment payment = paymentIn(PaymentStatus.PROCESSING);
        StubPaymentStore store = new StubPaymentStore(new VersionedPayment(payment, 3));
        StubLedger ledger = new StubLedger(exactPosting(payment));
        CompletePaymentAfterProviderSuccess useCase = useCase(store, ledger);

        PaymentCompletionResult result = useCase.complete(payment.tenantId(), payment.id());

        assertEquals(PaymentStatus.COMPLETED, result.payment().payment().status());
        assertEquals(4, result.payment().version());
        assertFalse(result.replay());
        assertTrue(store.compareAndSetCalled);
        assertEquals(payment.amount().amount(), ledger.request.amount());
        assertEquals(payment.amount().currency(), ledger.request.currency());
    }

    @Test
    void processingPaymentWithExistingPostingIsCriticalInconsistency() {
        Payment payment = paymentIn(PaymentStatus.PROCESSING);
        StubPaymentStore store = new StubPaymentStore(new VersionedPayment(payment, 3));
        StubLedger ledger = new StubLedger(exactPosting(payment));
        ledger.existing = Optional.of(exactPosting(payment));

        PaymentCompletionConsistencyException exception = assertThrows(
                PaymentCompletionConsistencyException.class,
                () -> useCase(store, ledger).complete(payment.tenantId(), payment.id())
        );

        assertEquals(
                PaymentCompletionConsistencyError.PROCESSING_WITH_EXISTING_POSTING,
                exception.error()
        );
        assertFalse(store.compareAndSetCalled);
        assertFalse(ledger.postCalled);
    }

    @Test
    void completedPaymentWithExactPostingReturnsOriginalLogicalResult() {
        Payment payment = paymentIn(PaymentStatus.COMPLETED);
        StubPaymentStore store = new StubPaymentStore(new VersionedPayment(payment, 4));
        StubLedger ledger = new StubLedger(exactPosting(payment));
        ledger.existing = Optional.of(exactPosting(payment));

        PaymentCompletionResult result = useCase(store, ledger)
                .complete(payment.tenantId(), payment.id());

        assertEquals(PaymentStatus.COMPLETED, result.payment().payment().status());
        assertEquals(4, result.payment().version());
        assertTrue(result.replay());
        assertFalse(store.compareAndSetCalled);
        assertFalse(ledger.postCalled);
    }

    @Test
    void completedPaymentWithoutPostingIsCriticalInconsistency() {
        Payment payment = paymentIn(PaymentStatus.COMPLETED);
        StubPaymentStore store = new StubPaymentStore(new VersionedPayment(payment, 4));
        StubLedger ledger = new StubLedger(exactPosting(payment));

        PaymentCompletionConsistencyException exception = assertThrows(
                PaymentCompletionConsistencyException.class,
                () -> useCase(store, ledger).complete(payment.tenantId(), payment.id())
        );

        assertEquals(
                PaymentCompletionConsistencyError.COMPLETED_WITHOUT_POSTING,
                exception.error()
        );
        assertFalse(store.compareAndSetCalled);
        assertFalse(ledger.postCalled);
    }

    @Test
    void completedPaymentWithAnyMismatchedPostingEvidenceIsCriticalInconsistency() {
        Payment payment = paymentIn(PaymentStatus.COMPLETED);
        LedgerPostingEvidence exact = exactPosting(payment);
        List<LedgerPostingEvidence> mismatches = List.of(
                evidence(exact, UUID.randomUUID(), exact.sourceType(), exact.sourceId(),
                        exact.currency(), exact.totalDebits(), exact.totalCredits(),
                        exact.entries(), exact.compensatesTransactionId()),
                evidence(exact, exact.tenantId(), "REVERSAL", exact.sourceId(),
                        exact.currency(), exact.totalDebits(), exact.totalCredits(),
                        exact.entries(), exact.compensatesTransactionId()),
                evidence(exact, exact.tenantId(), exact.sourceType(), UUID.randomUUID(),
                        exact.currency(), exact.totalDebits(), exact.totalCredits(),
                        exact.entries(), exact.compensatesTransactionId()),
                evidence(exact, exact.tenantId(), exact.sourceType(), exact.sourceId(),
                        Currency.getInstance("USD"), exact.totalDebits(), exact.totalCredits(),
                        exact.entries(), exact.compensatesTransactionId()),
                evidence(exact, exact.tenantId(), exact.sourceType(), exact.sourceId(),
                        exact.currency(), new BigDecimal("124.00"), exact.totalCredits(),
                        exact.entries(), exact.compensatesTransactionId()),
                evidence(exact, exact.tenantId(), exact.sourceType(), exact.sourceId(),
                        exact.currency(), exact.totalDebits(), new BigDecimal("124.00"),
                        exact.entries(), exact.compensatesTransactionId()),
                evidence(exact, exact.tenantId(), exact.sourceType(), exact.sourceId(),
                        exact.currency(), exact.totalDebits(), exact.totalCredits(),
                        List.of(exact.entries().getFirst()), exact.compensatesTransactionId()),
                evidence(exact, exact.tenantId(), exact.sourceType(), exact.sourceId(),
                        exact.currency(), exact.totalDebits(), exact.totalCredits(),
                        wrongDirectionEntries(exact.entries()), exact.compensatesTransactionId()),
                evidence(exact, exact.tenantId(), exact.sourceType(), exact.sourceId(),
                        exact.currency(), exact.totalDebits(), exact.totalCredits(),
                        wrongAccountEntries(exact.entries()), exact.compensatesTransactionId()),
                evidence(exact, exact.tenantId(), exact.sourceType(), exact.sourceId(),
                        exact.currency(), exact.totalDebits(), exact.totalCredits(),
                        exact.entries(), Optional.of(UUID.randomUUID()))
        );

        for (LedgerPostingEvidence mismatch : mismatches) {
            StubPaymentStore store = new StubPaymentStore(new VersionedPayment(payment, 4));
            StubLedger ledger = new StubLedger(exact);
            ledger.existing = Optional.of(mismatch);

            PaymentCompletionConsistencyException exception = assertThrows(
                    PaymentCompletionConsistencyException.class,
                    () -> useCase(store, ledger).complete(payment.tenantId(), payment.id())
            );
            assertEquals(
                    PaymentCompletionConsistencyError.COMPLETED_WITH_MISMATCHED_POSTING,
                    exception.error()
            );
            assertFalse(store.compareAndSetCalled);
        }
    }

    @Test
    void anyOtherPaymentStateReturnsTypedLifecycleFailureWithoutPosting() {
        Payment payment = paymentIn(PaymentStatus.APPROVED);
        StubPaymentStore store = new StubPaymentStore(new VersionedPayment(payment, 2));
        StubLedger ledger = new StubLedger(exactPosting(payment));

        PaymentLifecycleStateException exception = assertThrows(
                PaymentLifecycleStateException.class,
                () -> useCase(store, ledger).complete(payment.tenantId(), payment.id())
        );

        assertEquals(PaymentStatus.PROCESSING, exception.requiredStatus());
        assertEquals(PaymentStatus.APPROVED, exception.actualStatus());
        assertFalse(store.compareAndSetCalled);
        assertFalse(ledger.postCalled);
    }

    @Test
    void compareAndSetFailureIsTypedAfterThePostingAttempt() {
        Payment payment = paymentIn(PaymentStatus.PROCESSING);
        StubPaymentStore store = new StubPaymentStore(new VersionedPayment(payment, 3));
        store.compareAndSetResult = false;
        StubLedger ledger = new StubLedger(exactPosting(payment));

        assertThrows(
                PaymentOptimisticConcurrencyException.class,
                () -> useCase(store, ledger).complete(payment.tenantId(), payment.id())
        );

        assertTrue(ledger.postCalled);
        assertTrue(store.compareAndSetCalled);
    }

    private CompletePaymentAfterProviderSuccess useCase(
            StubPaymentStore store,
            StubLedger ledger
    ) {
        return new CompletePaymentAfterProviderSuccess(store, ledger);
    }

    private Payment paymentIn(PaymentStatus status) {
        UUID tenantId = UUID.randomUUID();
        Payment created = Payment.create(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), SAR),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("completion-unit-" + UUID.randomUUID())
        );
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

    private LedgerPostingEvidence exactPosting(Payment payment) {
        BigDecimal amount = payment.amount().amount();
        Currency currency = payment.amount().currency();
        return new LedgerPostingEvidence(
                UUID.randomUUID(),
                payment.tenantId(),
                "PAYMENT",
                payment.id().value(),
                currency,
                amount,
                amount,
                List.of(
                        entry("PROVIDER_CLEARING", "DEBIT", amount, currency),
                        entry("MERCHANT_PAYABLE", "CREDIT", amount, currency)
                ),
                Optional.empty()
        );
    }

    private LedgerPostingEntryEvidence entry(
            String accountCode,
            String direction,
            BigDecimal amount,
            Currency currency
    ) {
        return new LedgerPostingEntryEvidence(
                UUID.randomUUID(),
                accountCode,
                direction,
                amount,
                currency
        );
    }

    private List<LedgerPostingEntryEvidence> wrongDirectionEntries(
            List<LedgerPostingEntryEvidence> entries
    ) {
        return List.of(
                entry("PROVIDER_CLEARING", "CREDIT", entries.getFirst().amount(), SAR),
                entries.get(1)
        );
    }

    private List<LedgerPostingEntryEvidence> wrongAccountEntries(
            List<LedgerPostingEntryEvidence> entries
    ) {
        return List.of(
                entry("CUSTOMER_RECEIVABLE", "DEBIT", entries.getFirst().amount(), SAR),
                entries.get(1)
        );
    }

    private LedgerPostingEvidence evidence(
            LedgerPostingEvidence original,
            UUID tenantId,
            String sourceType,
            UUID sourceId,
            Currency currency,
            BigDecimal debits,
            BigDecimal credits,
            List<LedgerPostingEntryEvidence> entries,
            Optional<UUID> compensation
    ) {
        return new LedgerPostingEvidence(
                original.transactionId(),
                tenantId,
                sourceType,
                sourceId,
                currency,
                debits,
                credits,
                entries,
                compensation
        );
    }

    private static final class StubPaymentStore implements PaymentCompletionStore {

        private final VersionedPayment payment;
        private boolean compareAndSetResult = true;
        private boolean compareAndSetCalled;

        private StubPaymentStore(VersionedPayment payment) {
            this.payment = payment;
        }

        @Override
        public Optional<VersionedPayment> lockByTenantAndId(
                UUID tenantId,
                PaymentId paymentId
        ) {
            if (payment.payment().tenantId().equals(tenantId)
                    && payment.payment().id().equals(paymentId)) {
                return Optional.of(payment);
            }
            return Optional.empty();
        }

        @Override
        public boolean compareAndSet(Payment updatedPayment, long expectedVersion) {
            compareAndSetCalled = true;
            return compareAndSetResult;
        }
    }

    private static final class StubLedger implements PaymentSuccessLedger {

        private Optional<LedgerPostingEvidence> existing = Optional.empty();
        private final LedgerPostingEvidence posted;
        private PaymentSuccessPostingRequest request;
        private boolean postCalled;

        private StubLedger(LedgerPostingEvidence posted) {
            this.posted = posted;
        }

        @Override
        public Optional<LedgerPostingEvidence> findByPaymentSource(
                UUID tenantId,
                UUID paymentId
        ) {
            return existing;
        }

        @Override
        public LedgerPostingEvidence postPaymentSuccess(
                PaymentSuccessPostingRequest request
        ) {
            this.request = request;
            postCalled = true;
            return posted;
        }
    }
}
