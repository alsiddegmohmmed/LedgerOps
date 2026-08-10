package com.ledgerops.payment.infrastructure;

import com.ledgerops.payment.api.PaymentAttemptSnapshot;
import com.ledgerops.payment.api.PaymentDetailsQuery;
import com.ledgerops.payment.api.PaymentDetailsSnapshot;
import com.ledgerops.payment.api.PaymentNoteSnapshot;
import com.ledgerops.payment.application.PaymentDetailsStore;
import com.ledgerops.payment.application.PaymentNoteStore;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class PaymentJdbcDetailsStore implements PaymentDetailsStore, PaymentDetailsQuery {

    private final JdbcTemplate jdbcTemplate;
    private final PaymentNoteStore notes;

    PaymentJdbcDetailsStore(JdbcTemplate jdbcTemplate, PaymentNoteStore notes) {
        this.jdbcTemplate = jdbcTemplate;
        this.notes = notes;
    }

    @Override
    public Optional<PaymentDetailsSnapshot> findByTenantAndPayment(UUID tenantId, UUID paymentId) {
        List<PaymentDetailsSnapshot> payments = jdbcTemplate.query("""
                SELECT id, tenant_id, merchant_id, customer_id, amount, currency,
                       payment_method_category, status, created_at, updated_at
                  FROM payment.payments
                 WHERE tenant_id = ? AND id = ?
                """, (rs, rowNumber) -> {
            List<PaymentAttemptSnapshot> attempts = jdbcTemplate.query("""
                    SELECT id, sequence, provider_id, provider_idempotency_key, initiated_at
                      FROM payment.payment_attempts
                     WHERE tenant_id = ? AND payment_id = ?
                     ORDER BY sequence
                    """, (attempt, attemptRow) -> new PaymentAttemptSnapshot(
                    attempt.getObject("id", UUID.class),
                    attempt.getInt("sequence"),
                    attempt.getString("provider_id"),
                    attempt.getString("provider_idempotency_key"),
                    attempt.getTimestamp("initiated_at").toInstant()),
                    tenantId, paymentId);
            List<PaymentNoteSnapshot> paymentNotes = notes.findByPayment(tenantId, paymentId)
                    .stream()
                    .map(note -> new PaymentNoteSnapshot(
                            note.noteId(), note.tenantId(), note.paymentId(), note.merchantId(),
                            note.authorIssuer(), note.authorSubject(), note.content(),
                            note.createdAt()))
                    .toList();
            return new PaymentDetailsSnapshot(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("merchant_id", UUID.class),
                    rs.getObject("customer_id", UUID.class),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency"),
                    rs.getString("payment_method_category"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    attempts,
                    paymentNotes);
        }, tenantId, paymentId);
        return payments.stream().findFirst();
    }
}
