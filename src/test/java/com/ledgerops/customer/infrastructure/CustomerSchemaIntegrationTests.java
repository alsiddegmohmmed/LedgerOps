package com.ledgerops.customer.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class CustomerSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsDataMinimizedCustomerTableThroughFlyway() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'customer'
                  AND table_name = 'customers'
                ORDER BY ordinal_position
                """,
                String.class
        );

        assertEquals(
                List.of(
                        "id",
                        "tenant_id",
                        "merchant_id",
                        "customer_reference",
                        "status",
                        "version",
                        "created_at",
                        "updated_at"
                ),
                columns
        );
    }

    @Test
    void databaseRejectsCustomerWithoutTenantOrMerchantOwnership() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertCustomer(null, UUID.randomUUID())
        );
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertCustomer(UUID.randomUUID(), null)
        );
    }

    private void insertCustomer(UUID tenantId, UUID merchantId) {
        Timestamp now = Timestamp.from(Instant.parse("2026-07-18T00:00:00Z"));
        jdbcTemplate.update(
                """
                INSERT INTO customer.customers (
                    id,
                    tenant_id,
                    merchant_id,
                    customer_reference,
                    status,
                    created_at,
                    updated_at
                ) VALUES (?, CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                tenantId,
                merchantId,
                "ownerless-customer",
                "ACTIVE",
                now,
                now
        );
    }
}
