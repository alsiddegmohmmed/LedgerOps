package com.ledgerops.audit.infrastructure;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AuditRecordSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsAuditTableAndAppendOnlyTriggerThroughFlyway() {
        Boolean tableExists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = 'audit' AND table_name = 'audit_records'
                )
                """,
                Boolean.class
        );
        Integer triggerCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM pg_trigger
                WHERE tgrelid = 'audit.audit_records'::regclass
                  AND tgname = 'audit_records_append_only'
                """,
                Integer.class
        );

        assertEquals(Boolean.TRUE, tableExists);
        assertEquals(1, triggerCount);
    }
}
