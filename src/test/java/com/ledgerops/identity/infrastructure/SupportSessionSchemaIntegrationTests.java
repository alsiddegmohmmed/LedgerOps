package com.ledgerops.identity.infrastructure;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SupportSessionSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsSupportSessionsWithTenantOwnershipAndExactThirtyMinuteExpiry() {
        assertThat(tableExists()).isTrue();

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'identity'
                   AND table_name = 'support_sessions'
                   AND column_name IN (
                       'tenant_id', 'actor_issuer', 'actor_subject', 'reason',
                       'authentication_time', 'started_at', 'expires_at'
                   )
                """,
                Integer.class
        )).isEqualTo(7);

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM pg_constraint
                 WHERE conrelid = 'identity.support_sessions'::regclass
                   AND conname IN (
                       'fk_support_sessions_tenant',
                       'ck_support_sessions_auth_time',
                       'ck_support_sessions_expiry'
                   )
                """,
                Integer.class
        )).isEqualTo(3);
    }

    private boolean tableExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM information_schema.tables
                     WHERE table_schema = 'identity'
                       AND table_name = 'support_sessions'
                )
                """,
                Boolean.class
        ));
    }
}
