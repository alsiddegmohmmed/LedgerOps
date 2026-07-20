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
import com.ledgerops.ledger.domain.LedgerSourceReference;
import com.ledgerops.ledger.domain.LedgerSourceType;
import com.ledgerops.ledger.domain.LedgerTransaction;
import com.ledgerops.ledger.domain.LedgerTransactionId;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerPostingIntegrationTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant POSTED_AT = Instant.parse("2026-07-20T11:00:00Z");

    @Autowired
    private LedgerPostingService postingService;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void atomicallyPersistsOneBalancedPostingAndItsAccountRelationships() {
        UUID tenantId = UUID.randomUUID();
        LedgerAccount debitAccount = insertAccount(
                tenantId,
                AccountCode.CUSTOMER_RECEIVABLE,
                SAR
        );
        LedgerAccount creditAccount = insertAccount(
                tenantId,
                AccountCode.MERCHANT_PAYABLE,
                SAR
        );
        LedgerTransaction transaction = transaction(
                tenantId,
                UUID.randomUUID(),
                LedgerTransactionId.newId(),
                debitAccount,
                creditAccount,
                "125.00"
        );

        LedgerTransaction posted = postingService.post(transaction);

        assertEquals(transaction.id(), posted.id());
        assertEquals(1, transactionCount(transaction.id()));
        assertEquals(2, entryCount(transaction.id()));
        assertEquals(
                List.of(debitAccount.accountId().value(), creditAccount.accountId().value()),
                jdbcTemplate.queryForList(
                        """
                        SELECT account_id
                          FROM ledger.entries
                         WHERE tenant_id = ?
                           AND transaction_id = ?
                         ORDER BY entry_index
                        """,
                        UUID.class,
                        tenantId,
                        transaction.id().value()
                )
        );
    }

    @Test
    void missingAccountRejectsTheCompletePostingWithTypedFailure() {
        UUID tenantId = UUID.randomUUID();
        LedgerAccount missingDebit = LedgerAccount.create(
                LedgerAccountId.newId(),
                tenantId,
                AccountCode.CUSTOMER_RECEIVABLE,
                SAR,
                POSTED_AT
        );
        LedgerAccount creditAccount = insertAccount(
                tenantId,
                AccountCode.MERCHANT_PAYABLE,
                SAR
        );
        LedgerTransaction transaction = transaction(
                tenantId,
                UUID.randomUUID(),
                LedgerTransactionId.newId(),
                missingDebit,
                creditAccount,
                "10.00"
        );

        LedgerPostingException exception = assertThrows(
                LedgerPostingException.class,
                () -> postingService.post(transaction)
        );

        assertEquals(LedgerPostingError.ACCOUNT_NOT_FOUND, exception.error());
        assertNothingPersisted(transaction.id());
    }

    @Test
    void anotherTenantsAccountIsNotDisclosedAndRejectsTheCompletePosting() {
        UUID postingTenant = UUID.randomUUID();
        LedgerAccount foreignAccount = insertAccount(
                UUID.randomUUID(),
                AccountCode.CUSTOMER_RECEIVABLE,
                SAR
        );
        LedgerAccount localAccount = insertAccount(
                postingTenant,
                AccountCode.MERCHANT_PAYABLE,
                SAR
        );
        LedgerAccountReference forgedReference = new LedgerAccountReference(
                postingTenant,
                foreignAccount.accountId(),
                foreignAccount.accountCode(),
                foreignAccount.currency()
        );
        LedgerTransaction transaction = LedgerTransaction.post(
                LedgerTransactionId.newId(),
                postingTenant,
                source(postingTenant, UUID.randomUUID()),
                POSTED_AT,
                List.of(
                        LedgerEntry.debit(forgedReference, amount("10.00", SAR)),
                        LedgerEntry.credit(reference(localAccount), amount("10.00", SAR))
                )
        );

        LedgerPostingException exception = assertThrows(
                LedgerPostingException.class,
                () -> postingService.post(transaction)
        );

        assertEquals(LedgerPostingError.ACCOUNT_NOT_FOUND, exception.error());
        assertNothingPersisted(transaction.id());
    }

    @Test
    void persistedAccountCurrencyMismatchRejectsTheCompletePosting() {
        UUID tenantId = UUID.randomUUID();
        LedgerAccount sarAccount = insertAccount(
                tenantId,
                AccountCode.CUSTOMER_RECEIVABLE,
                SAR
        );
        LedgerAccount usdAccount = insertAccount(
                tenantId,
                AccountCode.MERCHANT_PAYABLE,
                USD
        );
        LedgerAccountReference forgedCurrency = new LedgerAccountReference(
                tenantId,
                sarAccount.accountId(),
                sarAccount.accountCode(),
                USD
        );
        LedgerTransaction transaction = LedgerTransaction.post(
                LedgerTransactionId.newId(),
                tenantId,
                source(tenantId, UUID.randomUUID()),
                POSTED_AT,
                List.of(
                        LedgerEntry.debit(forgedCurrency, amount("10.00", USD)),
                        LedgerEntry.credit(reference(usdAccount), amount("10.00", USD))
                )
        );

        LedgerPostingException exception = assertThrows(
                LedgerPostingException.class,
                () -> postingService.post(transaction)
        );

        assertEquals(
                LedgerPostingError.ACCOUNT_CURRENCY_MISMATCH,
                exception.error()
        );
        assertNothingPersisted(transaction.id());
    }

    @Test
    void persistedAccountCodeMismatchRejectsTheCompletePosting() {
        UUID tenantId = UUID.randomUUID();
        LedgerAccount debitAccount = insertAccount(
                tenantId,
                AccountCode.CUSTOMER_RECEIVABLE,
                SAR
        );
        LedgerAccount creditAccount = insertAccount(
                tenantId,
                AccountCode.MERCHANT_PAYABLE,
                SAR
        );
        LedgerAccountReference forgedCode = new LedgerAccountReference(
                tenantId,
                debitAccount.accountId(),
                AccountCode.PROVIDER_CLEARING,
                SAR
        );
        LedgerTransaction transaction = LedgerTransaction.post(
                LedgerTransactionId.newId(),
                tenantId,
                source(tenantId, UUID.randomUUID()),
                POSTED_AT,
                List.of(
                        LedgerEntry.debit(forgedCode, amount("10.00", SAR)),
                        LedgerEntry.credit(reference(creditAccount), amount("10.00", SAR))
                )
        );

        LedgerPostingException exception = assertThrows(
                LedgerPostingException.class,
                () -> postingService.post(transaction)
        );

        assertEquals(LedgerPostingError.ACCOUNT_CODE_MISMATCH, exception.error());
        assertNothingPersisted(transaction.id());
    }

    @Test
    void duplicateFinancialSourceCreatesExactlyOnePosting() {
        UUID tenantId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        LedgerAccount debitAccount = insertAccount(
                tenantId,
                AccountCode.PROVIDER_CLEARING,
                SAR
        );
        LedgerAccount creditAccount = insertAccount(
                tenantId,
                AccountCode.MERCHANT_PAYABLE,
                SAR
        );
        LedgerTransaction first = transaction(
                tenantId,
                sourceId,
                LedgerTransactionId.newId(),
                debitAccount,
                creditAccount,
                "50.00"
        );
        LedgerTransaction duplicate = transaction(
                tenantId,
                sourceId,
                LedgerTransactionId.newId(),
                debitAccount,
                creditAccount,
                "50.00"
        );

        postingService.post(first);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> postingService.post(duplicate)
        );
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                          FROM ledger.transactions
                         WHERE tenant_id = ?
                           AND source_type = 'PAYMENT'
                           AND source_id = ?
                        """,
                        Integer.class,
                        tenantId,
                        sourceId
                )
        );
        assertEquals(2, entryCount(first.id()));
        assertNothingPersisted(duplicate.id());
    }

    private LedgerAccount insertAccount(
            UUID tenantId,
            AccountCode accountCode,
            Currency currency
    ) {
        LedgerAccount account = LedgerAccount.create(
                LedgerAccountId.newId(),
                tenantId,
                accountCode,
                currency,
                POSTED_AT
        );
        accountRepository.insert(account);
        return account;
    }

    private LedgerTransaction transaction(
            UUID tenantId,
            UUID sourceId,
            LedgerTransactionId transactionId,
            LedgerAccount debitAccount,
            LedgerAccount creditAccount,
            String value
    ) {
        return LedgerTransaction.post(
                transactionId,
                tenantId,
                source(tenantId, sourceId),
                POSTED_AT,
                List.of(
                        LedgerEntry.debit(reference(debitAccount), amount(value, debitAccount.currency())),
                        LedgerEntry.credit(reference(creditAccount), amount(value, creditAccount.currency()))
                )
        );
    }

    private LedgerSourceReference source(UUID tenantId, UUID sourceId) {
        return new LedgerSourceReference(
                tenantId,
                LedgerSourceType.PAYMENT,
                sourceId
        );
    }

    private LedgerAccountReference reference(LedgerAccount account) {
        return new LedgerAccountReference(
                account.tenantId(),
                account.accountId(),
                account.accountCode(),
                account.currency()
        );
    }

    private LedgerAmount amount(String value, Currency currency) {
        return LedgerAmount.of(new BigDecimal(value), currency);
    }

    private int transactionCount(LedgerTransactionId transactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger.transactions WHERE id = ?",
                Integer.class,
                transactionId.value()
        );
    }

    private int entryCount(LedgerTransactionId transactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger.entries WHERE transaction_id = ?",
                Integer.class,
                transactionId.value()
        );
    }

    private void assertNothingPersisted(LedgerTransactionId transactionId) {
        assertEquals(0, transactionCount(transactionId));
        assertEquals(0, entryCount(transactionId));
    }
}
