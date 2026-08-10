package com.ledgerops.payment.infrastructure;

import com.ledgerops.payment.application.PaymentNoteStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
class PaymentJdbcNoteStore implements PaymentNoteStore {

    private final JdbcTemplate jdbcTemplate;

    PaymentJdbcNoteStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PaymentResource> findPayment(UUID tenantId, UUID paymentId) {
        return jdbcTemplate.query("""
                        SELECT tenant_id, id, merchant_id
                          FROM payment.payments
                         WHERE tenant_id = ? AND id = ?
                        """,
                (rs, rowNumber) -> new PaymentResource(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getObject("merchant_id", UUID.class)),
                tenantId, paymentId).stream().findFirst();
    }

    @Override
    public void append(Note note) {
        jdbcTemplate.update("""
                INSERT INTO payment.notes (
                    id, tenant_id, payment_id, merchant_id,
                    author_issuer, author_subject, content, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                note.noteId(),
                note.tenantId(),
                note.paymentId(),
                note.merchantId(),
                note.authorIssuer(),
                note.authorSubject(),
                note.content(),
                Timestamp.from(note.createdAt()));
    }

    @Override
    public List<Note> findByPayment(UUID tenantId, UUID paymentId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, payment_id, merchant_id,
                       author_issuer, author_subject, content, created_at
                  FROM payment.notes
                 WHERE tenant_id = ? AND payment_id = ?
                 ORDER BY created_at, id
                """, (rs, rowNumber) -> new Note(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("author_issuer"),
                rs.getString("author_subject"),
                rs.getString("content"),
                rs.getTimestamp("created_at").toInstant()),
                tenantId, paymentId);
    }
}
