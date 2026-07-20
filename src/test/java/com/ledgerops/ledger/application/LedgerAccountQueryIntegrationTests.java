package com.ledgerops.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAccountReference;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import com.ledgerops.ledger.domain.LedgerAmount;
import com.ledgerops.ledger.domain.LedgerEntry;
import com.ledgerops.ledger.domain.LedgerEntryDirection;
import com.ledgerops.ledger.domain.LedgerSourceReference;
import com.ledgerops.ledger.domain.LedgerSourceType;
import com.ledgerops.ledger.domain.LedgerTransaction;
import com.ledgerops.ledger.domain.LedgerTransactionId;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerAccountQueryIntegrationTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final Instant FIRST_POSTING = Instant.parse("2026-07-20T13:00:00Z");
    private static final Instant SECOND_POSTING = Instant.parse("2026-07-20T14:00:00Z");
    private static final Instant THIRD_POSTING = Instant.parse("2026-07-20T15:00:00Z");

    @Autowired
    private LedgerAccountQueryService queryService;

    @Autowired
    private LedgerPostingService postingService;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Test
    void derivesDateBoundedStatementAndBalanceFromImmutableEntries() {
        AccountPair accounts = accounts(UUID.randomUUID());
        post(accounts, FIRST_POSTING, "10.00", LedgerEntryDirection.DEBIT);
        post(accounts, SECOND_POSTING, "4.00", LedgerEntryDirection.CREDIT);
        post(accounts, THIRD_POSTING, "7.00", LedgerEntryDirection.DEBIT);

        LedgerAccountStatement statement = queryService.statement(
                new LedgerStatementQuery(
                        accounts.tenantId(),
                        accounts.target().accountId(),
                        FIRST_POSTING,
                        THIRD_POSTING,
                        0,
                        100
                )
        );
        LedgerAccountBalance balance = queryService.balance(
                accounts.tenantId(),
                accounts.target().accountId(),
                THIRD_POSTING
        );

        assertEquals(new BigDecimal("10.00"), statement.totalDebits());
        assertEquals(new BigDecimal("4.00"), statement.totalCredits());
        assertEquals(2, statement.totalEntries());
        assertEquals(2, statement.entries().size());
        assertEquals(FIRST_POSTING, statement.entries().get(0).postedAt());
        assertEquals(SECOND_POSTING, statement.entries().get(1).postedAt());
        assertEquals(LedgerEntryDirection.DEBIT, statement.entries().get(0).direction());
        assertEquals(LedgerEntryDirection.CREDIT, statement.entries().get(1).direction());
        assertEquals(new BigDecimal("10.00"), balance.totalDebits());
        assertEquals(new BigDecimal("4.00"), balance.totalCredits());
    }

    @Test
    void exclusiveBalanceBoundaryAndEmptyStatementReturnCurrencyScaledZeros() {
        AccountPair accounts = accounts(UUID.randomUUID());
        post(accounts, FIRST_POSTING, "10.00", LedgerEntryDirection.DEBIT);

        LedgerAccountBalance balance = queryService.balance(
                accounts.tenantId(),
                accounts.target().accountId(),
                FIRST_POSTING
        );
        LedgerAccountStatement statement = queryService.statement(
                new LedgerStatementQuery(
                        accounts.tenantId(),
                        accounts.target().accountId(),
                        FIRST_POSTING.minusSeconds(60),
                        FIRST_POSTING,
                        0,
                        10
                )
        );

        assertEquals(new BigDecimal("0.00"), balance.totalDebits());
        assertEquals(new BigDecimal("0.00"), balance.totalCredits());
        assertEquals(new BigDecimal("0.00"), statement.totalDebits());
        assertEquals(new BigDecimal("0.00"), statement.totalCredits());
        assertEquals(0, statement.totalEntries());
        assertEquals(List.of(), statement.entries());
    }

    @Test
    void paginationIsBoundedAndPreservesFullPeriodTotalsAndStableOrdering() {
        AccountPair accounts = accounts(UUID.randomUUID());
        post(accounts, FIRST_POSTING, "10.00", LedgerEntryDirection.DEBIT);
        post(accounts, SECOND_POSTING, "4.00", LedgerEntryDirection.CREDIT);
        post(accounts, THIRD_POSTING, "7.00", LedgerEntryDirection.DEBIT);

        LedgerAccountStatement page = queryService.statement(
                new LedgerStatementQuery(
                        accounts.tenantId(),
                        accounts.target().accountId(),
                        FIRST_POSTING,
                        THIRD_POSTING.plusSeconds(1),
                        1,
                        1
                )
        );

        assertEquals(3, page.totalEntries());
        assertEquals(new BigDecimal("17.00"), page.totalDebits());
        assertEquals(new BigDecimal("4.00"), page.totalCredits());
        assertEquals(1, page.entries().size());
        assertEquals(SECOND_POSTING, page.entries().getFirst().postedAt());
        assertEquals(LedgerSourceType.PAYMENT, page.entries().getFirst()
                .sourceReference().sourceType());
    }

    @Test
    void tenantScopedQueriesDoNotDiscloseAnotherTenantsAccountOrHistory() {
        AccountPair accounts = accounts(UUID.randomUUID());
        post(accounts, FIRST_POSTING, "10.00", LedgerEntryDirection.DEBIT);
        UUID otherTenant = UUID.randomUUID();

        LedgerAccountNotFoundException balanceFailure = assertThrows(
                LedgerAccountNotFoundException.class,
                () -> queryService.balance(
                        otherTenant,
                        accounts.target().accountId(),
                        SECOND_POSTING
                )
        );
        LedgerAccountNotFoundException statementFailure = assertThrows(
                LedgerAccountNotFoundException.class,
                () -> queryService.statement(new LedgerStatementQuery(
                        otherTenant,
                        accounts.target().accountId(),
                        FIRST_POSTING,
                        SECOND_POSTING,
                        0,
                        10
                ))
        );

        assertEquals(otherTenant, balanceFailure.tenantId());
        assertEquals(accounts.target().accountId(), balanceFailure.accountId());
        assertEquals(otherTenant, statementFailure.tenantId());
    }

    @Test
    void returnedStatementEntriesAreImmutable() {
        AccountPair accounts = accounts(UUID.randomUUID());
        post(accounts, FIRST_POSTING, "10.00", LedgerEntryDirection.DEBIT);
        LedgerAccountStatement statement = queryService.statement(
                new LedgerStatementQuery(
                        accounts.tenantId(),
                        accounts.target().accountId(),
                        FIRST_POSTING,
                        SECOND_POSTING,
                        0,
                        10
                )
        );

        assertThrows(
                UnsupportedOperationException.class,
                () -> statement.entries().clear()
        );
    }

    private AccountPair accounts(UUID tenantId) {
        LedgerAccount target = insertAccount(
                tenantId,
                AccountCode.CUSTOMER_RECEIVABLE
        );
        LedgerAccount counterpart = insertAccount(
                tenantId,
                AccountCode.MERCHANT_PAYABLE
        );
        return new AccountPair(tenantId, target, counterpart);
    }

    private LedgerAccount insertAccount(UUID tenantId, AccountCode accountCode) {
        LedgerAccount account = LedgerAccount.create(
                LedgerAccountId.newId(),
                tenantId,
                accountCode,
                SAR,
                FIRST_POSTING.minusSeconds(1)
        );
        accountRepository.insert(account);
        return account;
    }

    private void post(
            AccountPair accounts,
            Instant postedAt,
            String value,
            LedgerEntryDirection targetDirection
    ) {
        LedgerEntry targetEntry = entry(
                accounts.target(),
                value,
                targetDirection
        );
        LedgerEntry counterpartEntry = entry(
                accounts.counterpart(),
                value,
                targetDirection == LedgerEntryDirection.DEBIT
                        ? LedgerEntryDirection.CREDIT
                        : LedgerEntryDirection.DEBIT
        );
        postingService.post(LedgerTransaction.post(
                LedgerTransactionId.newId(),
                accounts.tenantId(),
                new LedgerSourceReference(
                        accounts.tenantId(),
                        LedgerSourceType.PAYMENT,
                        UUID.randomUUID()
                ),
                postedAt,
                List.of(targetEntry, counterpartEntry)
        ));
    }

    private LedgerEntry entry(
            LedgerAccount account,
            String value,
            LedgerEntryDirection direction
    ) {
        LedgerAccountReference reference = new LedgerAccountReference(
                account.tenantId(),
                account.accountId(),
                account.accountCode(),
                account.currency()
        );
        LedgerAmount amount = LedgerAmount.of(new BigDecimal(value), SAR);
        return direction == LedgerEntryDirection.DEBIT
                ? LedgerEntry.debit(reference, amount)
                : LedgerEntry.credit(reference, amount);
    }

    private record AccountPair(
            UUID tenantId,
            LedgerAccount target,
            LedgerAccount counterpart
    ) {
    }
}
