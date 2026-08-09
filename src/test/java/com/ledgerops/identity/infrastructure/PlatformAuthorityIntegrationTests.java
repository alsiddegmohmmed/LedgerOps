package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.PlatformAuthorityPort;
import com.ledgerops.identity.api.PlatformAuthorizationException;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = {
        "ledgerops.identity.platform-admin.bootstrap-enabled=true",
        "ledgerops.identity.platform-admin.issuer=https://issuer.example",
        "ledgerops.identity.platform-admin.subject=platform-admin"
})
class PlatformAuthorityIntegrationTests {

    @Autowired
    private PlatformAuthorityPort authority;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bootstrapCreatesOneIdempotentCoreMapping() {
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM identity.platform_admin_assignments
                 WHERE issuer = 'https://issuer.example'
                   AND subject = 'platform-admin'
                   AND role = 'PLATFORM_ADMIN'
                """,
                Integer.class
        )).isEqualTo(1);

        authority.requirePlatformAdmin(new AuthenticatedPrincipal(
                "HUMAN", "https://issuer.example", "platform-admin"));
    }

    @Test
    void rejectsNonHumanOrUnmappedPrincipals() {
        assertThatThrownBy(() -> authority.requirePlatformAdmin(
                new AuthenticatedPrincipal(
                        "SERVICE", "https://issuer.example", "platform-admin")))
                .isInstanceOf(PlatformAuthorizationException.class);
        assertThatThrownBy(() -> authority.requirePlatformAdmin(
                new AuthenticatedPrincipal(
                        "HUMAN", "https://issuer.example", "other-subject")))
                .isInstanceOf(PlatformAuthorizationException.class);
    }
}
