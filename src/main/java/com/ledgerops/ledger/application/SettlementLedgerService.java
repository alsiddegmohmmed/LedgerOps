package com.ledgerops.ledger.application;

import com.ledgerops.ledger.api.LedgerPostingEntryEvidence;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.SettlementLedger;
import com.ledgerops.ledger.api.SettlementLedgerError;
import com.ledgerops.ledger.api.SettlementLedgerException;
import com.ledgerops.ledger.api.SettlementPostingRequest;
import com.ledgerops.ledger.api.SettlementPostingType;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class SettlementLedgerService implements SettlementLedger {

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionStore transactions;
    private final LedgerPostingService posting;
    private final Clock clock;

    public SettlementLedgerService(
            LedgerAccountRepository accounts,
            LedgerTransactionStore transactions,
            LedgerPostingService posting,
            Clock clock
    ) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.posting = posting;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LedgerPostingEvidence> findBySettlementPostingSource(
            UUID tenantId,
            UUID settlementPostingId
    ) {
        return transactions.findBySource(
                tenantId, LedgerSourceType.SETTLEMENT_ADJUSTMENT, settlementPostingId)
                .map(this::toEvidence);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerPostingEvidence postSettlement(SettlementPostingRequest request) {
        Objects.requireNonNull(request, "Settlement posting request must not be null");
        Optional<LedgerPostingEvidence> existing = findBySettlementPostingSource(
                request.tenantId(), request.settlementPostingId());
        if (existing.isPresent()) {
            validateReplay(existing.get(), request);
            return existing.get();
        }

        LedgerAccount receivable = requiredAccount(request, AccountCode.SETTLEMENT_RECEIVABLE);
        LedgerAccount clearing = requiredAccount(request, AccountCode.PROVIDER_CLEARING);
        LedgerAmount amount = LedgerAmount.of(request.amount(), request.currency());
        LedgerEntry debit;
        LedgerEntry credit;
        if (request.postingType() == SettlementPostingType.PAYMENT) {
            debit = LedgerEntry.debit(reference(receivable), amount);
            credit = LedgerEntry.credit(reference(clearing), amount);
        } else {
            debit = LedgerEntry.debit(reference(clearing), amount);
            credit = LedgerEntry.credit(reference(receivable), amount);
        }
        LedgerTransaction transaction = LedgerTransaction.post(
                LedgerTransactionId.newId(), request.tenantId(),
                new LedgerSourceReference(
                        request.tenantId(), LedgerSourceType.SETTLEMENT_ADJUSTMENT,
                        request.settlementPostingId()),
                clock.instant(), List.of(debit, credit));
        try {
            return toEvidence(posting.post(transaction));
        } catch (DuplicateKeyException exception) {
            LedgerPostingEvidence raced = findBySettlementPostingSource(
                    request.tenantId(), request.settlementPostingId()).orElseThrow(
                    () -> new SettlementLedgerException(
                            SettlementLedgerError.SOURCE_ALREADY_POSTED,
                            "Settlement posting source conflicted but could not be reloaded", exception));
            validateReplay(raced, request);
            return raced;
        } catch (LedgerPostingException exception) {
            throw new SettlementLedgerException(
                    SettlementLedgerError.REQUIRED_ACCOUNT_INVALID,
                    "A required settlement Ledger account is invalid", exception);
        }
    }

    private LedgerAccount requiredAccount(SettlementPostingRequest request, AccountCode code) {
        LedgerAccount account = accounts.findByIdentity(
                request.tenantId(), code, request.currency()).orElseThrow(
                () -> new SettlementLedgerException(
                        SettlementLedgerError.REQUIRED_ACCOUNT_NOT_FOUND,
                        "Required " + code + " account does not exist for the tenant and currency"));
        if (account.status() != LedgerAccountStatus.ACTIVE) {
            throw new SettlementLedgerException(
                    SettlementLedgerError.REQUIRED_ACCOUNT_INVALID,
                    "Required " + code + " account is not ACTIVE");
        }
        return account;
    }

    private void validateReplay(LedgerPostingEvidence evidence, SettlementPostingRequest request) {
        String debitCode = request.postingType() == SettlementPostingType.PAYMENT
                ? AccountCode.SETTLEMENT_RECEIVABLE.name()
                : AccountCode.PROVIDER_CLEARING.name();
        String creditCode = request.postingType() == SettlementPostingType.PAYMENT
                ? AccountCode.PROVIDER_CLEARING.name()
                : AccountCode.SETTLEMENT_RECEIVABLE.name();
        boolean expectedEntries = evidence.entries().size() == 2
                && evidence.entries().stream().anyMatch(entry ->
                debitCode.equals(entry.accountCode())
                        && "DEBIT".equals(entry.direction())
                        && request.currency().equals(entry.currency())
                        && request.amount().compareTo(entry.amount()) == 0)
                && evidence.entries().stream().anyMatch(entry ->
                creditCode.equals(entry.accountCode())
                        && "CREDIT".equals(entry.direction())
                        && request.currency().equals(entry.currency())
                        && request.amount().compareTo(entry.amount()) == 0);
        if (!evidence.currency().equals(request.currency())
                || evidence.totalDebits().compareTo(request.amount()) != 0
                || evidence.totalCredits().compareTo(request.amount()) != 0
                || !expectedEntries
                || evidence.compensatesTransactionId().isPresent()) {
            throw new SettlementLedgerException(
                    SettlementLedgerError.SOURCE_POSTING_MISMATCH,
                    "Existing settlement source has different immutable posting evidence");
        }
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
                        entry.direction().name(), entry.amount().amount(), entry.amount().currency()))
                        .toList(),
                transaction.compensatesTransactionId().map(LedgerTransactionId::value));
    }
}
