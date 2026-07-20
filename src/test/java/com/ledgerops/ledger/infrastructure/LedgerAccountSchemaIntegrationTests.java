package com.ledgerops.ledger.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerAccountSchemaIntegrationTests {

    private static final Instant CREATED_AT = Instant.parse("2026-07-20T10:15:30Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsTheLedgerOwnedAccountTableWithTheExactApprovedColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'ledger'
                   AND table_name = 'accounts'
                 ORDER BY ordinal_position
                """,
                String.class
        );

        assertEquals(
                List.of(
                        "id",
                        "tenant_id",
                        "account_code",
                        "currency",
                        "status",
                        "created_at"
                ),
                columns
        );
    }

    @Test
    void supportsEveryApprovedCodeAndRejectsAnyOtherCodeOrStatus() {
        UUID tenantId = UUID.randomUUID();
        Arrays.stream(AccountCode.values()).forEach(accountCode -> insertAccount(
                UUID.randomUUID(),
                tenantId,
                accountCode.name(),
                accountCode == AccountCode.SETTLEMENT_RECEIVABLE ? "USD" : "SAR",
                "ACTIVE"
        ));

        assertEquals(
                AccountCode.values().length,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM ledger.accounts WHERE tenant_id = ?",
                        Integer.class,
                        tenantId
                )
        );
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAccount(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "UNAPPROVED_ACCOUNT",
                        "SAR",
                        "ACTIVE"
                )
        );
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAccount(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        AccountCode.CUSTOMER_RECEIVABLE.name(),
                        "SAR",
                        "SUSPENDED"
                )
        );
    }

    @Test
    void requiresTenantCurrencyAndValidCurrencyFormat() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAccount(
                        UUID.randomUUID(),
                        null,
                        AccountCode.MERCHANT_PAYABLE.name(),
                        "SAR",
                        "ACTIVE"
                )
        );
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAccount(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        AccountCode.MERCHANT_PAYABLE.name(),
                        "sar",
                        "ACTIVE"
                )
        );
    }

    @Test
    void enforcesTenantCodeCurrencyUniquenessAtTheDatabaseBoundary() {
        UUID tenantId = UUID.randomUUID();
        String code = AccountCode.REVERSAL_PAYABLE.name();
        insertAccount(UUID.randomUUID(), tenantId, code, "SAR", "ACTIVE");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertAccount(
                        UUID.randomUUID(),
                        tenantId,
                        code,
                        "SAR",
                        "ACTIVE"
                )
        );

        insertAccount(UUID.randomUUID(), UUID.randomUUID(), code, "SAR", "ACTIVE");
        insertAccount(UUID.randomUUID(), tenantId, code, "USD", "ACTIVE");
    }

    @Test
    void rejectsEveryAccountUpdateAndDeletion() {
        UUID accountId = UUID.randomUUID();
        insertAccount(
                accountId,
                UUID.randomUUID(),
                AccountCode.PLATFORM_FEE_REVENUE.name(),
                "SAR",
                "ACTIVE"
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "UPDATE ledger.accounts SET currency = 'USD' WHERE id = ?",
                        accountId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "DELETE FROM ledger.accounts WHERE id = ?",
                        accountId
                )
        );

        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM ledger.accounts WHERE id = ?",
                        Integer.class,
                        accountId
                )
        );
    }

    @Test
    void ledgerAccountSchemaHasNoCrossModuleForeignKeys() {
        Integer crossModuleForeignKeys = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
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
                   AND target_namespace.nspname <> 'ledger'
                """,
                Integer.class
        );

        assertEquals(0, crossModuleForeignKeys);
    }

    private void insertAccount(
            UUID accountId,
            UUID tenantId,
            String accountCode,
            String currency,
            String status
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO ledger.accounts (
                    id, tenant_id, account_code, currency, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                accountId,
                tenantId,
                accountCode,
                currency,
                status,
                Timestamp.from(CREATED_AT)
        );
    }
}
