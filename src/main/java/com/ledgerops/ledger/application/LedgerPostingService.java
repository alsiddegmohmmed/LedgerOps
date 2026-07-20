package com.ledgerops.ledger.application;

import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountReference;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import com.ledgerops.ledger.domain.LedgerAccountStatus;
import com.ledgerops.ledger.domain.LedgerEntry;
import com.ledgerops.ledger.domain.LedgerTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class LedgerPostingService {

    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionStore transactionStore;

    public LedgerPostingService(
            LedgerAccountRepository accountRepository,
            LedgerTransactionStore transactionStore
    ) {
        this.accountRepository = accountRepository;
        this.transactionStore = transactionStore;
    }

    @Transactional
    public LedgerTransaction post(LedgerTransaction transaction) {
        Objects.requireNonNull(transaction, "Ledger transaction must not be null");

        for (LedgerEntry entry : transaction.entries()) {
            validateAccount(transaction, entry.account());
        }

        transactionStore.insert(transaction);
        return transaction;
    }

    private void validateAccount(
            LedgerTransaction transaction,
            LedgerAccountReference reference
    ) {
        LedgerAccount account = accountRepository.findById(
                transaction.tenantId(),
                reference.accountId()
        ).orElseThrow(() -> new LedgerPostingException(
                LedgerPostingError.ACCOUNT_NOT_FOUND,
                "Referenced Ledger account does not exist for the transaction tenant"
        ));

        if (account.accountCode() != reference.accountCode()) {
            throw new LedgerPostingException(
                    LedgerPostingError.ACCOUNT_CODE_MISMATCH,
                    "Referenced Ledger account code does not match persisted identity"
            );
        }
        if (!account.currency().equals(transaction.currency())) {
            throw new LedgerPostingException(
                    LedgerPostingError.ACCOUNT_CURRENCY_MISMATCH,
                    "Referenced Ledger account currency does not match transaction currency"
            );
        }
        if (account.status() != LedgerAccountStatus.ACTIVE) {
            throw new LedgerPostingException(
                    LedgerPostingError.ACCOUNT_NOT_ACTIVE,
                    "Referenced Ledger account is not ACTIVE"
            );
        }
    }
}
