package com.ledgerops.payment.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsPaymentTableWithTenantWideIdempotencyConstraint() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'payment'
                   AND table_name = 'payments'
                 ORDER BY ordinal_position
                """,
                String.class
        );
        String constraint = jdbcTemplate.queryForObject(
                """
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = 'uk_payments_tenant_idempotency'
                """,
                String.class
        );

        assertEquals(
                List.of(
                        "id",
                        "tenant_id",
                        "merchant_id",
                        "customer_id",
                        "amount",
                        "currency",
                        "payment_method_category",
                        "idempotency_key",
                        "request_fingerprint",
                        "status",
                        "version",
                        "created_at",
                        "updated_at"
                ),
                columns
        );
        assertEquals("UNIQUE (tenant_id, idempotency_key)", constraint);
    }

    @Test
    void merchantIdIsNotPartOfTheIdempotencyBoundary() {
        UUID tenantId = UUID.randomUUID();
        insertPayment(tenantId, UUID.randomUUID(), "database-boundary", "10.00", "CREATED");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertPayment(
                        tenantId,
                        UUID.randomUUID(),
                        "database-boundary",
                        "10.00",
                        "CREATED"
                )
        );
    }

    @Test
    void databaseRejectsNonPositiveAmountAndUnknownStatus() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "zero-amount",
                        "0.00",
                        "CREATED"
                )
        );
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertPayment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "unknown-status",
                        "10.00",
                        "PROVIDER_SUBMISSION_PENDING"
                )
        );
    }

    @Test
    void acceptedFinalResultUsesExactPaymentOwnedIdentityAndAttemptRelationship() {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'payment'
                   AND table_name = 'accepted_final_provider_results'
                 ORDER BY ordinal_position
                """, String.class);
        String primaryKey = constraint("accepted_final_provider_results_pkey");
        String attemptForeignKey = constraint("fk_accepted_final_attempt");
        String categoryCheck = constraint("ck_accepted_final_category");

        assertEquals(List.of(
                "tenant_id", "payment_id", "attempt_id", "provider_evidence_id",
                "provider_result_id", "final_category", "provider_reference", "applied_at"
        ), columns);
        assertEquals("PRIMARY KEY (tenant_id, payment_id)", primaryKey);
        assertEquals("FOREIGN KEY (tenant_id, payment_id, attempt_id) REFERENCES "
                + "payment.payment_attempts(tenant_id, payment_id, id)", attemptForeignKey);
        assertTrue(categoryCheck.contains("SUCCESS"));
        assertTrue(categoryCheck.contains("DECLINED"));
        assertTrue(categoryCheck.contains("PERMANENT_FAILURE"));
    }

    private String constraint(String name) {
        return jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = ?
                """, String.class, name);
    }

    private void insertPayment(
            UUID tenantId,
            UUID merchantId,
            String idempotencyKey,
            String amount,
            String status
    ) {
        Timestamp now = Timestamp.from(Instant.parse("2026-07-19T00:00:00Z"));
        jdbcTemplate.update(
                """
                INSERT INTO payment.payments (
                    id,
                    tenant_id,
                    merchant_id,
                    customer_id,
                    amount,
                    currency,
                    payment_method_category,
                    idempotency_key,
                    request_fingerprint,
                    status,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                tenantId,
                merchantId,
                UUID.randomUUID(),
                new BigDecimal(amount),
                "SAR",
                "card",
                idempotencyKey,
                "a".repeat(64),
                status,
                now,
                now
        );
    }
}
