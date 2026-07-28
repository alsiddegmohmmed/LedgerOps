package com.ledgerops.identity.infrastructure;

import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.identity.domain.ApplicationUserId;
import com.ledgerops.identity.domain.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantAuthorizationSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgresAuthorizedTenantContextAdapter tenantContexts;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void rejectsEmptyMerchantSetsAndMerchantScopesForTenantWideRoles() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);
        jdbc.update("insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                membershipId, userId, UUID.randomUUID(), now, now);

        assertThatThrownBy(() -> jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')",
                assignmentId, membershipId)).isInstanceOf(Exception.class);

        jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'TENANT_ADMIN', 'TENANT_WIDE')",
                assignmentId, membershipId);
        assertThatThrownBy(() -> jdbc.update("insert into identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id) values (?, ?)",
                assignmentId, UUID.randomUUID())).isInstanceOf(Exception.class);
    }

    @Test
    void derivesPermissionsAndMerchantScopeFromActivePostgresMembership() {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        var context = transactions.execute(status -> {
            jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                    userId, "issuer-" + userId, "subject-" + userId, now, now);
            jdbc.update("insert into identity.tenant_memberships (id, application_user_id, tenant_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
                    membershipId, userId, tenantId, now, now);
            jdbc.update("insert into identity.tenant_role_assignments (id, membership_id, role, scope_mode) values (?, ?, 'MERCHANT_ADMIN', 'MERCHANT_SET')",
                    assignmentId, membershipId);
            jdbc.update("insert into identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id) values (?, ?)",
                    assignmentId, merchantId);
            return tenantContexts.find(new ApplicationUserId(userId), PrincipalType.HUMAN, null, tenantId);
        });

        assertThat(context).hasValueSatisfying(value -> {
            assertThat(value.scopeMode().name()).isEqualTo("MERCHANT_SET");
            assertThat(value.merchantIds()).containsExactly(merchantId);
            assertThat(value.permissions().stream().map(Enum::name))
                    .contains("MERCHANT_READ", "CREDENTIAL_MANAGE");
        });
    }

    @Test
    void resolvesAnActiveServiceCredentialToItsFixedMerchantScope() {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into identity.application_users (id, issuer, subject, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, ?, ?)",
                userId, "issuer-" + userId, "subject-" + userId, now, now);
        jdbc.update("insert into identity.service_credentials (id, application_user_id, client_id, tenant_id, merchant_id, status, created_at, updated_at) values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                credentialId, userId, "ledger-client", tenantId, merchantId, now, now);

        var context = tenantContexts.find(new ApplicationUserId(userId), PrincipalType.SERVICE,
                "ledger-client", tenantId);

        assertThat(context).hasValueSatisfying(value -> {
            assertThat(value.serviceCredentialId()).isEqualTo(credentialId);
            assertThat(value.permissions().stream().map(Enum::name)).containsExactly("PAYMENT_CREATE");
            assertThat(value.merchantIds()).containsExactly(merchantId);
        });
    }
}
