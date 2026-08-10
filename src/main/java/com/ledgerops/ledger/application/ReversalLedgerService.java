package com.ledgerops.ledger.application;

import com.ledgerops.ledger.api.LedgerPostingEntryEvidence;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.ReversalCompensationPostingRequest;
import com.ledgerops.ledger.api.ReversalLedger;
import com.ledgerops.ledger.api.ReversalLedgerError;
import com.ledgerops.ledger.api.ReversalLedgerException;
import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountReference;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import com.ledgerops.ledger.domain.LedgerAccountStatus;
import com.ledgerops.ledger.domain.LedgerAmount;
import com.ledgerops.ledger.domain.LedgerEntry;
import com.ledgerops.ledger.domain.LedgerSourceType;
import com.ledgerops.ledger.domain.LedgerTransaction;
import com.ledgerops.ledger.domain.LedgerTransactionId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReversalLedgerService implements ReversalLedger {

    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";
    private static final AccountCode MERCHANT_PAYABLE = AccountCode.MERCHANT_PAYABLE;
    private static final AccountCode PROVIDER_CLEARING = AccountCode.PROVIDER_CLEARING;

    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionStore transactionStore;
    private final LedgerPostingService postingService;
    private final Clock clock;

    public ReversalLedgerService(
            LedgerAccountRepository accountRepository,
            LedgerTransactionStore transactionStore,
            LedgerPostingService postingService,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.transactionStore = transactionStore;
        this.postingService = postingService;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LedgerPostingEvidence> findByReversalSource(
            UUID tenantId,
            UUID reversalId
    ) {
        return transactionStore.findBySource(
                tenantId,
                LedgerSourceType.REVERSAL,
                reversalId
        ).map(this::toEvidence);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerPostingEvidence postCompensation(
            ReversalCompensationPostingRequest request
    ) {
        Objects.requireNonNull(request, "Reversal compensation request must not be null");

        Optional<LedgerPostingEvidence> existing = findByReversalSource(
                request.tenantId(), request.reversalId());
        if (existing.isPresent()) {
            LedgerPostingEvidence posting = existing.orElseThrow();
            LedgerTransaction original = originalPaymentPosting(request);
            if (!matchesReversal(posting, request, original.id().value())) {
                throw new ReversalLedgerException(
                        ReversalLedgerError.REVERSAL_SOURCE_ALREADY_POSTED,
                        "Reversal source already has a mismatched Ledger posting");
            }
            return posting;
        }

        LedgerTransaction original = originalPaymentPosting(request);
        LedgerAccount merchantPayable = requiredAccount(request, MERCHANT_PAYABLE);
        LedgerAccount providerClearing = requiredAccount(request, PROVIDER_CLEARING);
        LedgerAmount amount = LedgerAmount.of(request.amount(), request.currency());
        LedgerTransaction compensation = LedgerTransaction.postCompensation(
                LedgerTransactionId.newId(),
                request.tenantId(),
                new com.ledgerops.ledger.domain.LedgerSourceReference(
                        request.tenantId(), LedgerSourceType.REVERSAL, request.reversalId()),
                original.id(),
                clock.instant(),
                List.of(
                        LedgerEntry.debit(reference(merchantPayable), amount),
                        LedgerEntry.credit(reference(providerClearing), amount)
                )
        );

        try {
            return toEvidence(postingService.post(compensation));
        } catch (DuplicateKeyException exception) {
            LedgerPostingEvidence raced = findByReversalSource(
                    request.tenantId(), request.reversalId()).orElseThrow(() ->
                    new ReversalLedgerException(
                            ReversalLedgerError.REVERSAL_SOURCE_ALREADY_POSTED,
                            "Reversal source conflict did not expose a durable posting",
                            exception));
            if (!matchesReversal(raced, request, original.id().value())) {
                throw new ReversalLedgerException(
                        ReversalLedgerError.REVERSAL_SOURCE_ALREADY_POSTED,
                        "Reversal source raced with a mismatched Ledger posting",
                        exception);
            }
            return raced;
        } catch (LedgerPostingException exception) {
            throw new ReversalLedgerException(
                    ReversalLedgerError.REQUIRED_ACCOUNT_INVALID,
                    "A required Reversal Ledger account is invalid",
                    exception);
        }
    }

    private LedgerTransaction originalPaymentPosting(
            ReversalCompensationPostingRequest request
    ) {
        LedgerTransaction original = transactionStore.findBySource(
                request.tenantId(), LedgerSourceType.PAYMENT, request.paymentId()
        ).orElseThrow(() -> new ReversalLedgerException(
                ReversalLedgerError.ORIGINAL_PAYMENT_POSTING_MISSING,
                "The original Payment Ledger posting does not exist"));
        if (!matchesOriginalPayment(original, request)) {
            throw new ReversalLedgerException(
                    ReversalLedgerError.ORIGINAL_PAYMENT_POSTING_MISMATCH,
                    "The original Payment Ledger posting does not match ADR-020");
        }
        return original;
    }

    private LedgerAccount requiredAccount(
            ReversalCompensationPostingRequest request,
            AccountCode code
    ) {
        LedgerAccount account = accountRepository.findByIdentity(
                request.tenantId(), code, request.currency()
        ).orElseThrow(() -> new ReversalLedgerException(
                ReversalLedgerError.REQUIRED_ACCOUNT_NOT_FOUND,
                "Required " + code + " account does not exist"));
        if (account.status() != LedgerAccountStatus.ACTIVE) {
            throw new ReversalLedgerException(
                    ReversalLedgerError.REQUIRED_ACCOUNT_INVALID,
                    "Required " + code + " account is not ACTIVE");
        }
        return account;
    }

    private boolean matchesOriginalPayment(
            LedgerTransaction posting,
            ReversalCompensationPostingRequest request
    ) {
        return posting.tenantId().equals(request.tenantId())
                && posting.sourceReference().sourceType() == LedgerSourceType.PAYMENT
                && posting.sourceReference().sourceId().equals(request.paymentId())
                && posting.compensatesTransactionId().isEmpty()
                && posting.currency().equals(request.currency())
                && sameAmount(posting.totalDebits(), request.amount())
                && sameAmount(posting.totalCredits(), request.amount())
                && posting.entries().size() == 2
                && posting.entries().stream().filter(entry ->
                        entry.account().accountCode() == PROVIDER_CLEARING
                                && entry.direction().name().equals(DEBIT)
                                && sameAmount(entry.amount().amount(), request.amount())
                                && entry.amount().currency().equals(request.currency())
                ).count() == 1
                && posting.entries().stream().filter(entry ->
                        entry.account().accountCode() == MERCHANT_PAYABLE
                                && entry.direction().name().equals(CREDIT)
                                && sameAmount(entry.amount().amount(), request.amount())
                                && entry.amount().currency().equals(request.currency())
                ).count() == 1;
    }

    private boolean matchesReversal(
            LedgerPostingEvidence posting,
            ReversalCompensationPostingRequest request,
            UUID originalTransactionId
    ) {
        return posting.tenantId().equals(request.tenantId())
                && posting.sourceType().equals(LedgerSourceType.REVERSAL.name())
                && posting.sourceId().equals(request.reversalId())
                && posting.compensatesTransactionId()
                        .filter(originalTransactionId::equals)
                        .isPresent()
                && posting.currency().equals(request.currency())
                && sameAmount(posting.totalDebits(), request.amount())
                && sameAmount(posting.totalCredits(), request.amount())
                && posting.entries().size() == 2
                && posting.entries().stream().filter(entry ->
                        entry.accountCode().equals(MERCHANT_PAYABLE.name())
                                && entry.direction().equals(DEBIT)
                                && sameAmount(entry.amount(), request.amount())
                                && entry.currency().equals(request.currency())
                ).count() == 1
                && posting.entries().stream().filter(entry ->
                        entry.accountCode().equals(PROVIDER_CLEARING.name())
                                && entry.direction().equals(CREDIT)
                                && sameAmount(entry.amount(), request.amount())
                                && entry.currency().equals(request.currency())
                ).count() == 1;
    }

    private LedgerAccountReference reference(LedgerAccount account) {
        return new LedgerAccountReference(
                account.tenantId(), account.accountId(), account.accountCode(), account.currency());
    }

    private LedgerPostingEvidence toEvidence(LedgerTransaction transaction) {
        return new LedgerPostingEvidence(
                transaction.id().value(), transaction.tenantId(),
                transaction.sourceReference().sourceType().name(),
                transaction.sourceReference().sourceId(), transaction.currency(),
                transaction.totalDebits(), transaction.totalCredits(),
                transaction.entries().stream().map(entry -> new LedgerPostingEntryEvidence(
                        entry.account().accountId().value(), entry.account().accountCode().name(),
                        entry.direction().name(), entry.amount().amount(), entry.amount().currency()
                )).toList(),
                transaction.compensatesTransactionId().map(LedgerTransactionId::value)
        );
    }

    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) == 0;
    }
}
