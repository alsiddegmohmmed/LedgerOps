package com.ledgerops.payment.infrastructure;

import com.ledgerops.payment.api.PaymentReconciliationPage;
import com.ledgerops.payment.api.PaymentReconciliationPageRequest;
import com.ledgerops.payment.api.PaymentReconciliationQuery;
import com.ledgerops.payment.api.PaymentReconciliationSubject;
import com.ledgerops.payment.api.ReconciliationSubjectCursor;
import com.ledgerops.payment.api.ReconciliationSubjectType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@Repository
class PaymentJdbcReconciliationQuery implements PaymentReconciliationQuery {

    private static final String SUBJECTS_SQL = """
            SELECT subject_type, subject_id, payment_id, tenant_id, merchant_id,
                   amount, currency, provider_id, provider_idempotency_key,
                   provider_evidence_id, provider_result_id, provider_reference,
                   financial_status, applied_at
              FROM (
                    SELECT 'PAYMENT' AS subject_type,
                           f.payment_id AS subject_id,
                           f.payment_id,
                           f.tenant_id,
                           p.merchant_id,
                           p.amount,
                           p.currency,
                           a.provider_id,
                           a.provider_idempotency_key,
                           f.provider_evidence_id,
                           f.provider_result_id,
                           f.provider_reference,
                           CASE WHEN f.final_category = 'SUCCESS'
                                THEN 'COMPLETED' ELSE 'FAILED' END AS financial_status,
                           f.applied_at
                      FROM payment.accepted_final_provider_results f
                      JOIN payment.payments p
                        ON p.tenant_id = f.tenant_id AND p.id = f.payment_id
                      JOIN payment.payment_attempts a
                        ON a.tenant_id = f.tenant_id
                       AND a.payment_id = f.payment_id
                       AND a.id = f.attempt_id
                    UNION ALL
                    SELECT 'REVERSAL' AS subject_type,
                           f.reversal_id AS subject_id,
                           f.payment_id,
                           f.tenant_id,
                           r.merchant_id,
                           r.amount,
                           r.currency,
                           a.provider_id,
                           a.provider_idempotency_key,
                           f.provider_evidence_id,
                           f.provider_result_id,
                           f.provider_reference,
                           CASE WHEN f.final_category = 'SUCCESS'
                                THEN 'COMPLETED' ELSE 'FAILED' END AS financial_status,
                           f.applied_at
                      FROM payment.accepted_final_reversal_results f
                      JOIN payment.reversals r
                        ON r.tenant_id = f.tenant_id AND r.id = f.reversal_id
                      JOIN payment.payments p
                        ON p.tenant_id = f.tenant_id AND p.id = f.payment_id
                      JOIN payment.payment_attempts a
                        ON a.tenant_id = f.tenant_id
                       AND a.payment_id = f.payment_id
                       AND a.id = f.attempt_id
                   ) subjects
             WHERE tenant_id = ?
               AND applied_at <= ?
            """;

    private final JdbcTemplate jdbc;

    PaymentJdbcReconciliationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PaymentReconciliationPage findPage(PaymentReconciliationPageRequest request) {
        StringBuilder sql = new StringBuilder(SUBJECTS_SQL);
        List<Object> arguments = new ArrayList<>();
        arguments.add(request.tenantId());
        arguments.add(Timestamp.from(request.sourceCutoff()));

        request.afterCursor().ifPresent(cursor -> {
            sql.append("""
                    AND (applied_at > ?
                         OR (applied_at = ? AND subject_id > ?)
                         OR (applied_at = ? AND subject_id = ? AND subject_type > ?))
                    """);
            arguments.add(Timestamp.from(cursor.appliedAt()));
            arguments.add(Timestamp.from(cursor.appliedAt()));
            arguments.add(cursor.subjectId());
            arguments.add(Timestamp.from(cursor.appliedAt()));
            arguments.add(cursor.subjectId());
            arguments.add(cursor.subjectType().name());
        });

        sql.append(" ORDER BY applied_at, subject_id, subject_type LIMIT ?");
        arguments.add(request.limit() + 1);

        List<PaymentReconciliationSubject> rows = jdbc.query(
                sql.toString(),
                (rs, row) -> new PaymentReconciliationSubject(
                        rs.getObject("tenant_id", UUID.class),
                        ReconciliationSubjectType.valueOf(rs.getString("subject_type")),
                        rs.getObject("subject_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getBigDecimal("amount"),
                        Currency.getInstance(rs.getString("currency")),
                        rs.getString("provider_id"),
                        rs.getString("provider_idempotency_key"),
                        rs.getObject("provider_evidence_id", UUID.class),
                        rs.getObject("provider_result_id", UUID.class),
                        rs.getString("provider_reference"),
                        rs.getString("financial_status"),
                        rs.getTimestamp("applied_at").toInstant()),
                arguments.toArray());

        boolean hasNext = rows.size() > request.limit();
        if (hasNext) {
            rows = new ArrayList<>(rows.subList(0, request.limit()));
        }
        ReconciliationSubjectCursor next = hasNext
                ? cursorFor(rows.getLast())
                : null;
        return new PaymentReconciliationPage(rows, next);
    }

    private ReconciliationSubjectCursor cursorFor(PaymentReconciliationSubject subject) {
        return new ReconciliationSubjectCursor(
                subject.appliedAt(), subject.subjectId(), subject.subjectType());
    }
}
