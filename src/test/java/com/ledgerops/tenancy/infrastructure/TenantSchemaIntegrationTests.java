package com.ledgerops.tenancy.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsTenantsTableThroughFlyway() {
        Boolean tableExists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'tenancy'
                      AND table_name = 'tenants'
                )
                """,
                Boolean.class
        );

        assertEquals(Boolean.TRUE, tableExists);
    }
}
