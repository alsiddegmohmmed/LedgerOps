package com.ledgerops.ledger.infrastructure;

import com.ledgerops.ledger.api.LedgerPostingEntryEvidence;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.LedgerSettlementEvidence;
import com.ledgerops.ledger.api.LedgerSettlementEvidenceQuery;
import com.ledgerops.ledger.api.LedgerSettlementSource;
import com.ledgerops.ledger.api.LedgerSettlementSourceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
class JdbcLedgerSettlementEvidenceQuery implements LedgerSettlementEvidenceQuery {

    private final JdbcTemplate jdbc;

    JdbcLedgerSettlementEvidenceQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<LedgerSettlementSource, LedgerSettlementEvidence> findBySources(
            UUID tenantId,
            java.util.Collection<LedgerSettlementSource> sources
    ) {
        if (sources == null || sources.isEmpty()) {
            return Map.of();
        }
        List<LedgerSettlementSource> distinct = sources.stream().distinct().toList();
        String conditions = String.join(" OR ",
                java.util.Collections.nCopies(distinct.size(), "(t.source_type = ? AND t.source_id = ?)"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId);
        for (LedgerSettlementSource source : distinct) {
            arguments.add(source.sourceType().name());
            arguments.add(source.sourceId());
        }
        String sql = """
                SELECT t.id AS transaction_id, t.tenant_id, t.source_type, t.source_id,
                       t.compensates_transaction_id, t.posted_at, t.currency,
                       t.debit_total, t.credit_total,
                       e.account_id, a.account_code, e.direction, e.amount,
                       e.currency AS entry_currency, e.entry_index
                  FROM ledger.transactions t
                  JOIN ledger.entries e
                    ON e.tenant_id = t.tenant_id AND e.transaction_id = t.id
                  JOIN ledger.accounts a
                    ON a.tenant_id = e.tenant_id AND a.id = e.account_id
                 WHERE t.tenant_id = ? AND (%s)
                 ORDER BY t.id, e.entry_index
                """.formatted(conditions);

        Map<LedgerSettlementSource, MutableEvidence> grouped = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            LedgerSettlementSource source = new LedgerSettlementSource(
                    LedgerSettlementSourceType.valueOf(rs.getString("source_type")),
                    rs.getObject("source_id", UUID.class));
            MutableEvidence evidence = grouped.get(source);
            if (evidence == null) {
                evidence = new MutableEvidence(
                        source,
                        rs.getTimestamp("posted_at").toInstant(),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("currency"),
                        rs.getBigDecimal("debit_total"),
                        rs.getBigDecimal("credit_total"),
                        rs.getObject("compensates_transaction_id", UUID.class));
                grouped.put(source, evidence);
            }
            evidence.entries.add(new LedgerPostingEntryEvidence(
                    rs.getObject("account_id", UUID.class),
                    rs.getString("account_code"),
                    rs.getString("direction"),
                    rs.getBigDecimal("amount"),
                    Currency.getInstance(rs.getString("entry_currency"))));
        }, arguments.toArray());

        Map<LedgerSettlementSource, LedgerSettlementEvidence> result = new LinkedHashMap<>();
        grouped.forEach((source, value) -> result.put(source, value.toEvidence()));
        return result;
    }

    private static final class MutableEvidence {
        private final LedgerSettlementSource source;
        private final Instant postedAt;
        private final UUID transactionId;
        private final UUID tenantId;
        private final String currency;
        private final BigDecimal debitTotal;
        private final BigDecimal creditTotal;
        private final UUID compensatesTransactionId;
        private final List<LedgerPostingEntryEvidence> entries = new ArrayList<>();

        private MutableEvidence(
                LedgerSettlementSource source,
                Instant postedAt,
                UUID transactionId,
                UUID tenantId,
                String currency,
                BigDecimal debitTotal,
                BigDecimal creditTotal,
                UUID compensatesTransactionId
        ) {
            this.source = source;
            this.postedAt = postedAt;
            this.transactionId = transactionId;
            this.tenantId = tenantId;
            this.currency = currency;
            this.debitTotal = debitTotal;
            this.creditTotal = creditTotal;
            this.compensatesTransactionId = compensatesTransactionId;
        }

        private LedgerSettlementEvidence toEvidence() {
            return new LedgerSettlementEvidence(
                    source,
                    postedAt,
                    new LedgerPostingEvidence(
                            transactionId,
                            tenantId,
                            source.sourceType().name(),
                            source.sourceId(),
                            Currency.getInstance(currency),
                            debitTotal,
                            creditTotal,
                            entries,
                            java.util.Optional.ofNullable(compensatesTransactionId)));
        }
    }
}
