package com.ledgerops.ledger.application;

import com.ledgerops.ledger.api.LedgerPostingEntryEvidence;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.PaymentSuccessLedger;
import com.ledgerops.ledger.api.PaymentSuccessLedgerError;
import com.ledgerops.ledger.api.PaymentSuccessLedgerException;
import com.ledgerops.ledger.api.PaymentSuccessPostingRequest;
import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountReference;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import com.ledgerops.ledger.domain.LedgerAccountStatus;
import com.ledgerops.ledger.domain.LedgerAmount;
import com.ledgerops.ledger.domain.LedgerEntry;
import com.ledgerops.ledger.domain.LedgerSourceReference;
import com.ledgerops.ledger.domain.LedgerSourceType;
import com.ledgerops.ledger.domain.LedgerTransaction;
import com.ledgerops.ledger.domain.LedgerTransactionId;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentSuccessLedgerService implements PaymentSuccessLedger {

    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionStore transactionStore;
    private final LedgerPostingService postingService;
    private final Clock clock;

    public PaymentSuccessLedgerService(
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
    public Optional<LedgerPostingEvidence> findByPaymentSource(
            UUID tenantId,
            UUID paymentId
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        return transactionStore.findBySource(
                tenantId,
                LedgerSourceType.PAYMENT,
                paymentId
        ).map(this::toEvidence);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerPostingEvidence postPaymentSuccess(
            PaymentSuccessPostingRequest request
    ) {
        Objects.requireNonNull(request, "Payment-success request must not be null");
        LedgerAccount providerClearing = requiredAccount(
                request,
                AccountCode.PROVIDER_CLEARING
        );
        LedgerAccount merchantPayable = requiredAccount(
                request,
                AccountCode.MERCHANT_PAYABLE
        );
        LedgerAmount fullAmount = LedgerAmount.of(request.amount(), request.currency());
        LedgerTransaction posting = LedgerTransaction.post(
                LedgerTransactionId.newId(),
                request.tenantId(),
                new LedgerSourceReference(
                        request.tenantId(),
                        LedgerSourceType.PAYMENT,
                        request.paymentId()
                ),
                clock.instant(),
                List.of(
                        LedgerEntry.debit(reference(providerClearing), fullAmount),
                        LedgerEntry.credit(reference(merchantPayable), fullAmount)
                )
        );

        try {
            return toEvidence(postingService.post(posting));
        } catch (DuplicateKeyException exception) {
            throw new PaymentSuccessLedgerException(
                    PaymentSuccessLedgerError.PAYMENT_SOURCE_ALREADY_POSTED,
                    "The Payment financial source already has a Ledger posting",
                    exception
            );
        } catch (LedgerPostingException exception) {
            throw new PaymentSuccessLedgerException(
                    PaymentSuccessLedgerError.REQUIRED_ACCOUNT_INVALID,
                    "A required Payment-success Ledger account is invalid",
                    exception
            );
        }
    }

    private LedgerAccount requiredAccount(
            PaymentSuccessPostingRequest request,
            AccountCode accountCode
    ) {
        LedgerAccount account = accountRepository.findByIdentity(
                request.tenantId(),
                accountCode,
                request.currency()
        ).orElseThrow(() -> new PaymentSuccessLedgerException(
                PaymentSuccessLedgerError.REQUIRED_ACCOUNT_NOT_FOUND,
                "Required " + accountCode + " account does not exist for the tenant and currency"
        ));

        if (account.status() != LedgerAccountStatus.ACTIVE) {
            throw new PaymentSuccessLedgerException(
                    PaymentSuccessLedgerError.REQUIRED_ACCOUNT_INVALID,
                    "Required " + accountCode + " account is not ACTIVE"
            );
        }
        return account;
    }

    private LedgerAccountReference reference(LedgerAccount account) {
        return new LedgerAccountReference(
                account.tenantId(),
                account.accountId(),
                account.accountCode(),
                account.currency()
        );
    }

    private LedgerPostingEvidence toEvidence(LedgerTransaction transaction) {
        return new LedgerPostingEvidence(
                transaction.id().value(),
                transaction.tenantId(),
                transaction.sourceReference().sourceType().name(),
                transaction.sourceReference().sourceId(),
                transaction.currency(),
                transaction.totalDebits(),
                transaction.totalCredits(),
                transaction.entries().stream()
                        .map(entry -> new LedgerPostingEntryEvidence(
                                entry.account().accountId().value(),
                                entry.account().accountCode().name(),
                                entry.direction().name(),
                                entry.amount().amount(),
                                entry.amount().currency()
                        ))
                        .toList(),
                transaction.compensatesTransactionId()
                        .map(LedgerTransactionId::value)
        );
    }
}
