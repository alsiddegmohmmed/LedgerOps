package com.ledgerops.merchant.infrastructure;

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
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class MerchantSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsMerchantOwnedTableThroughFlyway() {
        Boolean tableExists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'merchant'
                      AND table_name = 'merchants'
                )
                """,
                Boolean.class
        );

        assertEquals(Boolean.TRUE, tableExists);
    }

    @Test
    void databaseRejectsMerchantWithoutTenantOwnership() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO merchant.merchants (
                            id,
                            tenant_id,
                            name,
                            status,
                            created_at,
                            updated_at
                        ) VALUES (?, CAST(? AS UUID), ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        null,
                        "Ownerless Merchant",
                        "ACTIVE",
                        Timestamp.from(now),
                        Timestamp.from(now)
                )
        );
    }
}
