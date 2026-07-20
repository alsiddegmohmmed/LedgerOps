package com.ledgerops.ledger.infrastructure;

import com.ledgerops.ledger.application.LedgerAccountQueryStore;
import com.ledgerops.ledger.application.LedgerStatementData;
import com.ledgerops.ledger.application.LedgerStatementEntry;
import com.ledgerops.ledger.application.LedgerStatementQuery;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAmount;
import com.ledgerops.ledger.domain.LedgerEntryDirection;
import com.ledgerops.ledger.domain.LedgerSourceReference;
import com.ledgerops.ledger.domain.LedgerSourceType;
import com.ledgerops.ledger.domain.LedgerTransactionId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@Repository
class LedgerAccountJdbcQueryStore implements LedgerAccountQueryStore {

    private static final String BALANCE_SQL = """
            SELECT coalesce(sum(entry.amount) FILTER (
                       WHERE entry.direction = 'DEBIT'
                   ), 0) AS total_debits,
                   coalesce(sum(entry.amount) FILTER (
                       WHERE entry.direction = 'CREDIT'
                   ), 0) AS total_credits
              FROM ledger.entries entry
              JOIN ledger.transactions transaction_record
                ON transaction_record.tenant_id = entry.tenant_id
               AND transaction_record.id = entry.transaction_id
             WHERE entry.tenant_id = ?
               AND entry.account_id = ?
               AND transaction_record.posted_at < ?
            """;

    private static final String STATEMENT_SQL = """
            WITH matching_entries AS (
                SELECT transaction_record.id AS transaction_id,
                       entry.entry_index,
                       transaction_record.source_type,
                       transaction_record.source_id,
                       transaction_record.posted_at,
                       entry.direction,
                       entry.amount,
                       entry.currency
                  FROM ledger.entries entry
                  JOIN ledger.transactions transaction_record
                    ON transaction_record.tenant_id = entry.tenant_id
                   AND transaction_record.id = entry.transaction_id
                 WHERE entry.tenant_id = ?
                   AND entry.account_id = ?
                   AND transaction_record.posted_at >= ?
                   AND transaction_record.posted_at < ?
            ),
            statement_summary AS (
                SELECT coalesce(sum(amount) FILTER (
                           WHERE direction = 'DEBIT'
                       ), 0) AS total_debits,
                       coalesce(sum(amount) FILTER (
                           WHERE direction = 'CREDIT'
                       ), 0) AS total_credits,
                       count(*) AS total_entries
                  FROM matching_entries
            ),
            statement_page AS (
                SELECT *
                  FROM matching_entries
                 ORDER BY posted_at, transaction_id, entry_index
                 LIMIT ? OFFSET ?
            )
            SELECT statement_summary.total_debits,
                   statement_summary.total_credits,
                   statement_summary.total_entries,
                   statement_page.transaction_id,
                   statement_page.entry_index,
                   statement_page.source_type,
                   statement_page.source_id,
                   statement_page.posted_at,
                   statement_page.direction,
                   statement_page.amount,
                   statement_page.currency
              FROM statement_summary
              LEFT JOIN statement_page ON true
             ORDER BY statement_page.posted_at,
                      statement_page.transaction_id,
                      statement_page.entry_index
            """;

    private final JdbcTemplate jdbcTemplate;

    LedgerAccountJdbcQueryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LedgerBalanceData summarizeBefore(
            UUID tenantId,
            LedgerAccountId accountId,
            Instant toExclusive
    ) {
        return jdbcTemplate.queryForObject(
                BALANCE_SQL,
                (resultSet, rowNumber) -> new LedgerBalanceData(
                        resultSet.getBigDecimal("total_debits"),
                        resultSet.getBigDecimal("total_credits")
                ),
                tenantId,
                accountId.value(),
                Timestamp.from(toExclusive)
        );
    }

    @Override
    public LedgerStatementData findStatement(LedgerStatementQuery query) {
        List<StatementRow> rows = jdbcTemplate.query(
                STATEMENT_SQL,
                (resultSet, rowNumber) -> mapStatementRow(
                        resultSet,
                        query.tenantId()
                ),
                query.tenantId(),
                query.accountId().value(),
                Timestamp.from(query.fromInclusive()),
                Timestamp.from(query.toExclusive()),
                query.limit(),
                query.offset()
        );

        StatementRow summary = rows.getFirst();
        List<LedgerStatementEntry> entries = new ArrayList<>();
        for (StatementRow row : rows) {
            if (row.entry() != null) {
                entries.add(row.entry());
            }
        }
        return new LedgerStatementData(
                summary.totalDebits(),
                summary.totalCredits(),
                summary.totalEntries(),
                entries
        );
    }

    private StatementRow mapStatementRow(ResultSet resultSet, UUID tenantId)
            throws SQLException {
        UUID transactionId = resultSet.getObject("transaction_id", UUID.class);
        LedgerStatementEntry entry = transactionId == null
                ? null
                : new LedgerStatementEntry(
                        LedgerTransactionId.from(transactionId),
                        resultSet.getInt("entry_index"),
                        new LedgerSourceReference(
                                tenantId,
                                LedgerSourceType.valueOf(
                                        resultSet.getString("source_type")
                                ),
                                resultSet.getObject("source_id", UUID.class)
                        ),
                        resultSet.getTimestamp("posted_at").toInstant(),
                        LedgerEntryDirection.valueOf(
                                resultSet.getString("direction")
                        ),
                        LedgerAmount.of(
                                resultSet.getBigDecimal("amount"),
                                Currency.getInstance(resultSet.getString("currency"))
                        )
                );
        return new StatementRow(
                resultSet.getBigDecimal("total_debits"),
                resultSet.getBigDecimal("total_credits"),
                resultSet.getLong("total_entries"),
                entry
        );
    }

    private record StatementRow(
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            long totalEntries,
            LedgerStatementEntry entry
    ) {
    }
}
