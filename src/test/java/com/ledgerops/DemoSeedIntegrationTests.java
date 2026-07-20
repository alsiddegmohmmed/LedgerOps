package com.ledgerops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class DemoSeedIntegrationTests {

    private static final UUID DEMO_TENANT = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void syntheticRelease01SeedLoadsRepeatablyAgainstTheMigratedSchema() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new FileSystemResource("docs/demo/release-0.1-seed.sql")
        );

        populator.execute(dataSource);
        populator.execute(dataSource);

        assertEquals(1, count("tenancy.tenants", "id"));
        assertEquals(1, count("merchant.merchants", "tenant_id"));
        assertEquals(1, count("customer.customers", "tenant_id"));
        assertEquals(6, count("ledger.accounts", "tenant_id"));
        assertEquals(1, count("risk.risk_profiles", "tenant_id"));
        assertEquals(1, count("risk.payment_amount_threshold_rules", "tenant_id"));
    }

    private int count(String table, String tenantColumn) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + tenantColumn + " = ?",
                Integer.class,
                DEMO_TENANT
        );
    }
}
