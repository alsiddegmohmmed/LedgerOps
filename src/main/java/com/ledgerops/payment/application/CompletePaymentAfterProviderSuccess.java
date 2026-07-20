package com.ledgerops.payment.application;

import com.ledgerops.ledger.api.LedgerPostingEntryEvidence;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.PaymentSuccessLedger;
import com.ledgerops.ledger.api.PaymentSuccessLedgerError;
import com.ledgerops.ledger.api.PaymentSuccessLedgerException;
import com.ledgerops.ledger.api.PaymentSuccessPostingRequest;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class CompletePaymentAfterProviderSuccess {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            CompletePaymentAfterProviderSuccess.class
    );
    private static final String PAYMENT_SOURCE = "PAYMENT";
    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";
    private static final String PROVIDER_CLEARING = "PROVIDER_CLEARING";
    private static final String MERCHANT_PAYABLE = "MERCHANT_PAYABLE";

    private final PaymentCompletionStore paymentStore;
    private final PaymentSuccessLedger ledger;

    public CompletePaymentAfterProviderSuccess(
            PaymentCompletionStore paymentStore,
            PaymentSuccessLedger ledger
    ) {
        this.paymentStore = paymentStore;
        this.ledger = ledger;
    }

    @Transactional
    public PaymentCompletionResult complete(UUID tenantId, PaymentId paymentId) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");

        VersionedPayment current = paymentStore.lockByTenantAndId(tenantId, paymentId)
                .orElseThrow(() -> new PaymentLifecycleNotFoundException(
                        tenantId,
                        paymentId
                ));
        Payment payment = current.payment();
        Optional<LedgerPostingEvidence> existing = ledger.findByPaymentSource(
                tenantId,
                paymentId.value()
        );

        if (payment.status() == PaymentStatus.COMPLETED) {
            return replayCompletedPayment(current, existing);
        }
        if (payment.status() != PaymentStatus.PROCESSING) {
            throw new PaymentLifecycleStateException(
                    payment.id(),
                    PaymentStatus.PROCESSING,
                    payment.status()
            );
        }
        if (existing.isPresent()) {
            throw consistencyFailure(
                    payment,
                    PaymentCompletionConsistencyError.PROCESSING_WITH_EXISTING_POSTING,
                    "PROCESSING Payment already has a PAYMENT-source Ledger posting"
            );
        }

        LedgerPostingEvidence posted;
        try {
            posted = ledger.postPaymentSuccess(new PaymentSuccessPostingRequest(
                    payment.tenantId(),
                    payment.id().value(),
                    payment.amount().amount(),
                    payment.amount().currency()
            ));
        } catch (PaymentSuccessLedgerException exception) {
            if (exception.error() == PaymentSuccessLedgerError.PAYMENT_SOURCE_ALREADY_POSTED) {
                LOGGER.error(
                        "Payment completion consistency failure tenantId={} paymentId={} error={}",
                        payment.tenantId(),
                        payment.id().value(),
                        PaymentCompletionConsistencyError.PROCESSING_WITH_EXISTING_POSTING
                );
                throw new PaymentCompletionConsistencyException(
                        payment.id(),
                        PaymentCompletionConsistencyError.PROCESSING_WITH_EXISTING_POSTING,
                        "PROCESSING Payment acquired a concurrent PAYMENT-source posting",
                        exception
                );
            }
            throw exception;
        }
        if (!matches(payment, posted)) {
            throw consistencyFailure(
                    payment,
                    PaymentCompletionConsistencyError.NEW_POSTING_MISMATCH,
                    "New Payment-success posting does not match the approved template"
            );
        }

        Payment completed = payment.complete();
        if (!paymentStore.compareAndSet(completed, current.version())) {
            throw new PaymentOptimisticConcurrencyException(
                    payment.id(),
                    current.version()
            );
        }

        PaymentCompletionResult result = new PaymentCompletionResult(
                new VersionedPayment(completed, Math.addExact(current.version(), 1)),
                posted.transactionId(),
                false
        );
        LOGGER.info(
                "Payment completed after provider success tenantId={} paymentId={} ledgerTransactionId={}",
                payment.tenantId(),
                payment.id().value(),
                posted.transactionId()
        );
        return result;
    }

    private PaymentCompletionResult replayCompletedPayment(
            VersionedPayment current,
            Optional<LedgerPostingEvidence> existing
    ) {
        Payment payment = current.payment();
        LedgerPostingEvidence posting = existing.orElseThrow(() -> consistencyFailure(
                payment,
                PaymentCompletionConsistencyError.COMPLETED_WITHOUT_POSTING,
                "COMPLETED Payment has no PAYMENT-source Ledger posting"
        ));
        if (!matches(payment, posting)) {
            throw consistencyFailure(
                    payment,
                    PaymentCompletionConsistencyError.COMPLETED_WITH_MISMATCHED_POSTING,
                    "COMPLETED Payment has a mismatched PAYMENT-source Ledger posting"
            );
        }
        LOGGER.info(
                "Payment completion replayed tenantId={} paymentId={} ledgerTransactionId={}",
                payment.tenantId(),
                payment.id().value(),
                posting.transactionId()
        );
        return new PaymentCompletionResult(current, posting.transactionId(), true);
    }

    private boolean matches(Payment payment, LedgerPostingEvidence posting) {
        BigDecimal amount = payment.amount().amount();
        Currency currency = payment.amount().currency();
        List<LedgerPostingEntryEvidence> entries = posting.entries();

        return posting.tenantId().equals(payment.tenantId())
                && posting.sourceType().equals(PAYMENT_SOURCE)
                && posting.sourceId().equals(payment.id().value())
                && posting.currency().equals(currency)
                && sameAmount(posting.totalDebits(), amount)
                && sameAmount(posting.totalCredits(), amount)
                && entries.size() == 2
                && entries.stream().filter(entry -> entryMatches(
                        entry,
                        PROVIDER_CLEARING,
                        DEBIT,
                        amount,
                        currency
                )).count() == 1
                && entries.stream().filter(entry -> entryMatches(
                        entry,
                        MERCHANT_PAYABLE,
                        CREDIT,
                        amount,
                        currency
                )).count() == 1
                && posting.compensatesTransactionId().isEmpty();
    }

    private boolean entryMatches(
            LedgerPostingEntryEvidence entry,
            String accountCode,
            String direction,
            BigDecimal amount,
            Currency currency
    ) {
        return entry.accountCode().equals(accountCode)
                && entry.direction().equals(direction)
                && sameAmount(entry.amount(), amount)
                && entry.currency().equals(currency);
    }

    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) == 0;
    }

    private PaymentCompletionConsistencyException consistencyFailure(
            Payment payment,
            PaymentCompletionConsistencyError error,
            String message
    ) {
        LOGGER.error(
                "Payment completion consistency failure tenantId={} paymentId={} error={}",
                payment.tenantId(),
                payment.id().value(),
                error
        );
        return new PaymentCompletionConsistencyException(payment.id(), error, message);
    }
}
