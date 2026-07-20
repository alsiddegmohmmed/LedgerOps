package com.ledgerops.ledger.application;

import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class LedgerAccountQueryService {

    private final LedgerAccountRepository accountRepository;
    private final LedgerAccountQueryStore queryStore;

    public LedgerAccountQueryService(
            LedgerAccountRepository accountRepository,
            LedgerAccountQueryStore queryStore
    ) {
        this.accountRepository = accountRepository;
        this.queryStore = queryStore;
    }

    @Transactional(readOnly = true)
    public LedgerAccountBalance balance(
            UUID tenantId,
            LedgerAccountId accountId,
            Instant asOfExclusive
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(accountId, "Ledger account ID must not be null");
        Objects.requireNonNull(asOfExclusive, "Balance boundary must not be null");
        LedgerAccount account = requireAccount(tenantId, accountId);
        LedgerAccountQueryStore.LedgerBalanceData balance = queryStore.summarizeBefore(
                tenantId,
                accountId,
                asOfExclusive
        );
        return new LedgerAccountBalance(
                accountId,
                account.currency(),
                balance.totalDebits(),
                balance.totalCredits(),
                asOfExclusive
        );
    }

    @Transactional(readOnly = true)
    public LedgerAccountStatement statement(LedgerStatementQuery query) {
        Objects.requireNonNull(query, "Ledger statement query must not be null");
        LedgerAccount account = requireAccount(query.tenantId(), query.accountId());
        LedgerStatementData data = queryStore.findStatement(query);
        return new LedgerAccountStatement(
                query.accountId(),
                account.currency(),
                query.fromInclusive(),
                query.toExclusive(),
                data.totalDebits(),
                data.totalCredits(),
                data.totalEntries(),
                query.offset(),
                query.limit(),
                data.entries()
        );
    }

    private LedgerAccount requireAccount(
            UUID tenantId,
            LedgerAccountId accountId
    ) {
        return accountRepository.findById(tenantId, accountId)
                .orElseThrow(() -> new LedgerAccountNotFoundException(
                        tenantId,
                        accountId
                ));
    }
}
