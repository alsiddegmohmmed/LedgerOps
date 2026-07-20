package com.ledgerops.ledger.infrastructure;

import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import com.ledgerops.ledger.domain.LedgerAccountStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class LedgerAccountJdbcStore implements LedgerAccountRepository {

    private static final String INSERT_SQL = """
            INSERT INTO ledger.accounts (
                id,
                tenant_id,
                account_code,
                currency,
                status,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id,
                   tenant_id,
                   account_code,
                   currency,
                   status,
                   created_at
              FROM ledger.accounts
             WHERE tenant_id = ?
               AND id = ?
            """;

    private static final String FIND_BY_IDENTITY_SQL = """
            SELECT id,
                   tenant_id,
                   account_code,
                   currency,
                   status,
                   created_at
              FROM ledger.accounts
             WHERE tenant_id = ?
               AND account_code = ?
               AND currency = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    LedgerAccountJdbcStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(LedgerAccount account) {
        Objects.requireNonNull(account, "Ledger account must not be null");
        jdbcTemplate.update(
                INSERT_SQL,
                account.accountId().value(),
                account.tenantId(),
                account.accountCode().value(),
                account.currency().getCurrencyCode(),
                account.status().name(),
                Timestamp.from(account.createdAt())
        );
    }

    @Override
    public Optional<LedgerAccount> findById(
            UUID tenantId,
            LedgerAccountId accountId
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(accountId, "Ledger account ID must not be null");
        return jdbcTemplate.query(
                FIND_BY_ID_SQL,
                this::mapAccount,
                tenantId,
                accountId.value()
        ).stream().findFirst();
    }

    @Override
    public Optional<LedgerAccount> findByIdentity(
            UUID tenantId,
            AccountCode accountCode,
            Currency currency
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(accountCode, "Account code must not be null");
        Objects.requireNonNull(currency, "Ledger account currency must not be null");
        return jdbcTemplate.query(
                FIND_BY_IDENTITY_SQL,
                this::mapAccount,
                tenantId,
                accountCode.value(),
                currency.getCurrencyCode()
        ).stream().findFirst();
    }

    private LedgerAccount mapAccount(ResultSet resultSet, int rowNumber)
            throws SQLException {
        LedgerAccountStatus.valueOf(resultSet.getString("status"));
        return LedgerAccount.create(
                LedgerAccountId.from(resultSet.getObject("id", UUID.class)),
                resultSet.getObject("tenant_id", UUID.class),
                AccountCode.from(resultSet.getString("account_code")),
                Currency.getInstance(resultSet.getString("currency")),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
