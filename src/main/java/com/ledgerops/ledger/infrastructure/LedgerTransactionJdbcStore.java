package com.ledgerops.ledger.infrastructure;

import com.ledgerops.ledger.application.LedgerTransactionStore;
import com.ledgerops.ledger.domain.LedgerEntry;
import com.ledgerops.ledger.domain.LedgerTransaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

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
}
