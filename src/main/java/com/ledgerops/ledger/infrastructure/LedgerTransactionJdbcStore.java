package com.ledgerops.ledger.infrastructure;

import com.ledgerops.ledger.application.LedgerTransactionStore;
import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAccountReference;
import com.ledgerops.ledger.domain.LedgerAmount;
import com.ledgerops.ledger.domain.LedgerEntry;
import com.ledgerops.ledger.domain.LedgerEntryDirection;
import com.ledgerops.ledger.domain.LedgerSourceReference;
import com.ledgerops.ledger.domain.LedgerSourceType;
import com.ledgerops.ledger.domain.LedgerTransaction;
import com.ledgerops.ledger.domain.LedgerTransactionId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class LedgerTransactionJdbcStore implements LedgerTransactionStore {

    private static final String INSERT_TRANSACTION_SQL = """
            INSERT INTO ledger.transactions (
                id,
                tenant_id,
                source_type,
                source_id,
                compensates_transaction_id,
                posted_at,
                currency,
                entry_count,
                debit_total,
                credit_total
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_ENTRY_SQL = """
            INSERT INTO ledger.entries (
                tenant_id,
                transaction_id,
                entry_index,
                account_id,
                direction,
                amount,
                currency
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_TRANSACTION_BY_SOURCE_SQL = """
            SELECT id,
                   tenant_id,
                   source_type,
                   source_id,
                   compensates_transaction_id,
                   posted_at
              FROM ledger.transactions
             WHERE tenant_id = ?
               AND source_type = ?
               AND source_id = ?
            """;

    private static final String FIND_ENTRIES_SQL = """
            SELECT entry.account_id,
                   account.account_code,
                   entry.direction,
                   entry.amount,
                   entry.currency
              FROM ledger.entries entry
              JOIN ledger.accounts account
                ON account.tenant_id = entry.tenant_id
               AND account.id = entry.account_id
             WHERE entry.tenant_id = ?
               AND entry.transaction_id = ?
             ORDER BY entry.entry_index
            """;

    private final JdbcTemplate jdbcTemplate;

    LedgerTransactionJdbcStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(LedgerTransaction transaction) {
        jdbcTemplate.update(
                INSERT_TRANSACTION_SQL,
                transaction.id().value(),
                transaction.tenantId(),
                transaction.sourceReference().sourceType().name(),
                transaction.sourceReference().sourceId(),
                transaction.compensatesTransactionId()
                        .map(transactionId -> transactionId.value())
                        .orElse(null),
                Timestamp.from(transaction.postedAt()),
                transaction.currency().getCurrencyCode(),
                transaction.entries().size(),
                transaction.totalDebits(),
                transaction.totalCredits()
        );

        List<Object[]> entryParameters = new ArrayList<>();
        for (int index = 0; index < transaction.entries().size(); index++) {
            LedgerEntry entry = transaction.entries().get(index);
            entryParameters.add(new Object[]{
                    transaction.tenantId(),
                    transaction.id().value(),
                    index,
                    entry.account().accountId().value(),
                    entry.direction().name(),
                    entry.amount().amount(),
                    entry.amount().currency().getCurrencyCode()
            });
        }
        jdbcTemplate.batchUpdate(INSERT_ENTRY_SQL, entryParameters);
    }

    @Override
    public Optional<LedgerTransaction> findBySource(
            UUID tenantId,
            LedgerSourceType sourceType,
            UUID sourceId
    ) {
        return jdbcTemplate.query(
                FIND_TRANSACTION_BY_SOURCE_SQL,
                (resultSet, rowNumber) -> mapHeader(resultSet),
                tenantId,
                sourceType.name(),
                sourceId
        ).stream().findFirst().map(this::mapTransaction);
    }

    private StoredTransactionHeader mapHeader(ResultSet resultSet) throws SQLException {
        return new StoredTransactionHeader(
                LedgerTransactionId.from(resultSet.getObject("id", UUID.class)),
                resultSet.getObject("tenant_id", UUID.class),
                LedgerSourceType.valueOf(resultSet.getString("source_type")),
                resultSet.getObject("source_id", UUID.class),
                resultSet.getObject("compensates_transaction_id", UUID.class),
                resultSet.getTimestamp("posted_at").toInstant()
        );
    }

    private LedgerTransaction mapTransaction(StoredTransactionHeader header) {
        LedgerSourceReference source = new LedgerSourceReference(
                header.tenantId(),
                header.sourceType(),
                header.sourceId()
        );
        List<LedgerEntry> entries = jdbcTemplate.query(
                FIND_ENTRIES_SQL,
                (entryResultSet, rowNumber) -> mapEntry(
                        entryResultSet,
                        header.tenantId()
                ),
                header.tenantId(),
                header.transactionId().value()
        );

        if (header.compensatesTransactionId() == null) {
            return LedgerTransaction.post(
                    header.transactionId(),
                    header.tenantId(),
                    source,
                    header.postedAt(),
                    entries
            );
        }
        return LedgerTransaction.postCompensation(
                header.transactionId(),
                header.tenantId(),
                source,
                LedgerTransactionId.from(header.compensatesTransactionId()),
                header.postedAt(),
                entries
        );
    }

    private LedgerEntry mapEntry(ResultSet resultSet, UUID tenantId)
            throws SQLException {
        Currency currency = Currency.getInstance(resultSet.getString("currency"));
        LedgerAccountReference account = new LedgerAccountReference(
                tenantId,
                LedgerAccountId.from(resultSet.getObject("account_id", UUID.class)),
                AccountCode.from(resultSet.getString("account_code")),
                currency
        );
        LedgerAmount amount = LedgerAmount.of(
                resultSet.getBigDecimal("amount"),
                currency
        );

        return switch (LedgerEntryDirection.valueOf(resultSet.getString("direction"))) {
            case DEBIT -> LedgerEntry.debit(account, amount);
            case CREDIT -> LedgerEntry.credit(account, amount);
        };
    }

    private record StoredTransactionHeader(
            LedgerTransactionId transactionId,
            UUID tenantId,
            LedgerSourceType sourceType,
            UUID sourceId,
            UUID compensatesTransactionId,
            Instant postedAt
    ) {
    }
}
