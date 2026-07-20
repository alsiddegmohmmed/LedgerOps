package com.ledgerops.ledger.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.ledger.application.LedgerPostingService;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerPostingSchemaIntegrationTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final Instant POSTED_AT = Instant.parse("2026-07-20T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private LedgerPostingService postingService;

    @Test
    void createsOnlyTheExpectedLedgerOwnedTablesAndSameSchemaRelationships() {
        assertEquals(
                List.of("accounts", "entries", "transactions"),
                jdbcTemplate.queryForList(
                        """
                        SELECT table_name
                          FROM information_schema.tables
                         WHERE table_schema = 'ledger'
                         ORDER BY table_name
                        """,
                        String.class
                )
        );

        List<String> referencedSchemas = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT target_namespace.nspname
                  FROM pg_constraint constraint_record
                  JOIN pg_class source_table
                    ON source_table.oid = constraint_record.conrelid
                  JOIN pg_namespace source_namespace
                    ON source_namespace.oid = source_table.relnamespace
                  JOIN pg_class target_table
                    ON target_table.oid = constraint_record.confrelid
                  JOIN pg_namespace target_namespace
                    ON target_namespace.oid = target_table.relnamespace
                 WHERE source_namespace.nspname = 'ledger'
                   AND constraint_record.contype = 'f'
                """,
                String.class
        );

        assertEquals(List.of("ledger"), referencedSchemas);
    }

    @Test
    void deferredDatabaseCheckRejectsAnUnbalancedEntrySetAndRollsBackEverything() {
        UUID tenantId = UUID.randomUUID();
        LedgerAccount debitAccount = insertAccount(
                tenantId,
                AccountCode.CUSTOMER_RECEIVABLE
        );
        LedgerAccount creditAccount = insertAccount(
                tenantId,
                AccountCode.MERCHANT_PAYABLE
        );
        UUID transactionId = UUID.randomUUID();

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> transactionTemplate.executeWithoutResult(status -> {
                    insertTransaction(transactionId, tenantId, null, 2, "10.00", "10.00");
                    insertEntry(transactionId, tenantId, 0, debitAccount, "DEBIT", "10.00");
                    insertEntry(transactionId, tenantId, 1, creditAccount, "CREDIT", "9.00");
                })
        );

        assertTrue(
                exception instanceof TransactionException
                        || exception instanceof DataAccessException
        );
        assertEquals(0, transactionCount(transactionId));
        assertEquals(0, entryCount(transactionId));
    }

    @Test
    void postedTransactionsAndEntriesCannotBeUpdatedOrDeleted() {
        UUID tenantId = UUID.randomUUID();
        LedgerAccount debitAccount = insertAccount(
                tenantId,
                AccountCode.PROVIDER_CLEARING
        );
        LedgerAccount creditAccount = insertAccount(
                tenantId,
                AccountCode.MERCHANT_PAYABLE
        );
        LedgerTransaction transaction = post(
                tenantId,
                debitAccount,
                creditAccount
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "UPDATE ledger.transactions SET posted_at = now() WHERE id = ?",
                        transaction.id().value()
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "UPDATE ledger.entries SET amount = 11 WHERE transaction_id = ?",
                        transaction.id().value()
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "DELETE FROM ledger.entries WHERE transaction_id = ?",
                        transaction.id().value()
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "DELETE FROM ledger.transactions WHERE id = ?",
                        transaction.id().value()
                )
        );

        assertEquals(1, transactionCount(transaction.id().value()));
        assertEquals(2, entryCount(transaction.id().value()));
    }

    @Test
    void compensationReferenceMustResolveInsideTheSameLedgerTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        assertThrows(
                DataAccessException.class,
                () -> insertTransaction(
                        transactionId,
                        tenantId,
                        UUID.randomUUID(),
                        2,
                        "10.00",
                        "10.00"
                )
        );
        assertEquals(0, transactionCount(transactionId));
    }

    private LedgerTransaction post(
            UUID tenantId,
            LedgerAccount debitAccount,
            LedgerAccount creditAccount
    ) {
        LedgerTransaction transaction = LedgerTransaction.post(
                LedgerTransactionId.newId(),
                tenantId,
                new LedgerSourceReference(
                        tenantId,
                        LedgerSourceType.PAYMENT,
                        UUID.randomUUID()
                ),
                POSTED_AT,
                List.of(
                        LedgerEntry.debit(reference(debitAccount), amount("10.00")),
                        LedgerEntry.credit(reference(creditAccount), amount("10.00"))
                )
        );
        return postingService.post(transaction);
    }

    private LedgerAccount insertAccount(UUID tenantId, AccountCode accountCode) {
        LedgerAccount account = LedgerAccount.create(
                LedgerAccountId.newId(),
                tenantId,
                accountCode,
                SAR,
                POSTED_AT
        );
        accountRepository.insert(account);
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

    private LedgerAmount amount(String value) {
        return LedgerAmount.of(new BigDecimal(value), SAR);
    }

    private void insertTransaction(
            UUID transactionId,
            UUID tenantId,
            UUID compensatesTransactionId,
            int entryCount,
            String debitTotal,
            String creditTotal
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO ledger.transactions (
                    id, tenant_id, source_type, source_id,
                    compensates_transaction_id, posted_at, currency,
                    entry_count, debit_total, credit_total
                ) VALUES (?, ?, 'PAYMENT', ?, ?, ?, 'SAR', ?, ?, ?)
                """,
                transactionId,
                tenantId,
                UUID.randomUUID(),
                compensatesTransactionId,
                Timestamp.from(POSTED_AT),
                entryCount,
                new BigDecimal(debitTotal),
                new BigDecimal(creditTotal)
        );
    }

    private void insertEntry(
            UUID transactionId,
            UUID tenantId,
            int entryIndex,
            LedgerAccount account,
            String direction,
            String amount
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO ledger.entries (
                    tenant_id, transaction_id, entry_index,
                    account_id, direction, amount, currency
                ) VALUES (?, ?, ?, ?, ?, ?, 'SAR')
                """,
                tenantId,
                transactionId,
                entryIndex,
                account.accountId().value(),
                direction,
                new BigDecimal(amount)
        );
    }

    private int transactionCount(UUID transactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger.transactions WHERE id = ?",
                Integer.class,
                transactionId
        );
    }

    private int entryCount(UUID transactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger.entries WHERE transaction_id = ?",
                Integer.class,
                transactionId
        );
    }
}
