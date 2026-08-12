package com.ledgerops.casework.infrastructure;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class CorrectionRequestSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsTheTenantSafeCorrectionRequestSchema() {
        assertThat(tableExists("casework", "correction_requests")).isTrue();
        assertThat(constraintExists("uk_correction_request_target")).isTrue();
        assertThat(constraintExists("fk_correction_request_case")).isTrue();
        assertThat(constraintExists("fk_correction_request_settlement_posting")).isTrue();
        assertThat(constraintExists("fk_correction_request_original_ledger")).isTrue();
        assertThat(constraintExists("fk_correction_request_compensation_ledger")).isTrue();
        assertThat(constraintExists("ck_correction_request_status_shape")).isTrue();
    }

    @Test
    void makesSettlementPostingIdentityTenantSafeForCorrectionReferences() {
        assertThat(constraintExists("uk_settlement_instruction_tenant_posting")).isTrue();
    }

    private boolean tableExists(String schema, String table) {
        Integer count = jdbc.queryForObject(
                """
                        SELECT count(*)
                          FROM information_schema.tables
                         WHERE table_schema = ? AND table_name = ?
                        """,
                Integer.class,
                schema,
                table
        );
        return count != null && count == 1;
    }

    private boolean constraintExists(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = ?",
                Integer.class,
                name
        );
        return count != null && count == 1;
    }
}
