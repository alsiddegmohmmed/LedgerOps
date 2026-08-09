package com.ledgerops.administration.application;

import com.ledgerops.administration.api.TenantActivationCommand;
import com.ledgerops.administration.api.TenantActivationResult;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.tenancy.api.TenantReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = {
        "ledgerops.identity.platform-admin.bootstrap-enabled=true",
        "ledgerops.identity.platform-admin.issuer=https://issuer.example",
        "ledgerops.identity.platform-admin.subject=platform-admin"
})
class TenantActivationIntegrationTests {

    private static final AuthenticatedPrincipal PLATFORM_ADMIN =
            new AuthenticatedPrincipal(
                    "HUMAN", "https://issuer.example", "platform-admin");

    @Autowired
    private TenantActivationService activation;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void rejectsActivationUntilInitialAdminIsActiveAndOnboardingIsConsistent() {
        UUID tenantId = insertTenant();
        insertMerchant(tenantId);

        assertThatThrownBy(() -> activation.activate(command(tenantId)))
                .isInstanceOf(TenantActivationPrerequisitesException.class);

        assertThat(statusOfTenant(tenantId)).isEqualTo("PENDING_ACTIVATION");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM messaging.outbox WHERE aggregate_id = ?",
                Integer.class,
                tenantId
        )).isZero();
    }

    @Test
    void activatesTenantAfterAllPrerequisitesAndRecordsLifecycleEvidence() {
        UUID tenantId = insertTenant();
        insertMerchant(tenantId);
        insertAcceptedInitialAdmin(tenantId);

        TenantActivationResult result = activation.activate(command(tenantId));

        assertThat(result.tenant().value()).isEqualTo(tenantId);
        assertThat(statusOfTenant(tenantId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM tenancy.tenants WHERE id = ?",
                Long.class,
                tenantId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM messaging.outbox "
                        + "WHERE producer_name = 'tenancy' AND aggregate_id = ? "
                        + "AND deduplication_key = ?",
                Integer.class,
                tenantId,
                "tenant-event:" + tenantId + ":1"
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_records "
                        + "WHERE action_type = 'tenant.active' AND target_id = ?",
                Integer.class,
                tenantId.toString()
        )).isEqualTo(1);
    }

    @Test
    void rejectsAnActorThatIsNotTheConfiguredPlatformAdmin() {
        UUID tenantId = insertTenant();

        assertThatThrownBy(() -> activation.activate(new TenantActivationCommand(
                TenantReference.from(tenantId),
                new AuthenticatedPrincipal("HUMAN", "https://issuer.example", "other"),
                UUID.randomUUID(),
                UUID.randomUUID()
        ))).isInstanceOf(com.ledgerops.identity.api.PlatformAuthorizationException.class);

        assertThat(statusOfTenant(tenantId)).isEqualTo("PENDING_ACTIVATION");
    }

    private TenantActivationCommand command(UUID tenantId) {
        return new TenantActivationCommand(
                TenantReference.from(tenantId),
                PLATFORM_ADMIN,
                UUID.randomUUID(),
                UUID.randomUUID()
        );
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO tenancy.tenants (
                    id, name, default_currency, default_locale, status,
                    version, created_at, updated_at
                ) VALUES (?, ?, 'SAR', 'en-SA', 'PENDING_ACTIVATION', 0, ?, ?)
                """,
                tenantId,
                "Activation Tenant " + tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return tenantId;
    }

    private void insertMerchant(UUID tenantId) {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO merchant.merchants (
                    id, tenant_id, name, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                merchantId,
                tenantId,
                "Activation Merchant " + merchantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private void insertAcceptedInitialAdmin(UUID tenantId) {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(10);
        Instant consumedAt = Instant.now();
        jdbc.update(
                """
                INSERT INTO identity.application_users (
                    id, issuer, subject, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                userId,
                "user-issuer-" + userId,
                "user-subject-" + userId,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );
        jdbc.update(
                """
                INSERT INTO identity.tenant_memberships (
                    id, application_user_id, tenant_id, status, is_initial,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', TRUE, 1, ?, ?)
                """,
                membershipId, userId, tenantId,
                Timestamp.from(createdAt), Timestamp.from(consumedAt)
        );
        jdbc.update(
                """
                INSERT INTO identity.tenant_role_assignments
                    (id, membership_id, role, scope_mode)
                VALUES (?, ?, 'TENANT_ADMIN', 'TENANT_WIDE')
                """,
                assignmentId, membershipId
        );
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    INSERT INTO identity.invitations (
                        id, tenant_id, membership_id, intended_email, token_hash,
                        status, version, created_at, expires_at, updated_at
                    ) VALUES (?, ?, ?, 'admin@example.com', ?, 'PENDING', 0, ?, ?, ?)
                    """,
                    invitationId,
                    tenantId,
                    membershipId,
                    "c".repeat(64),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt.plusSeconds(7 * 24 * 60 * 60L)),
                    Timestamp.from(createdAt)
            );
            jdbc.update(
                    """
                    INSERT INTO identity.invitation_grants (
                        invitation_id, assignment_id, tenant_id, role, scope_mode
                    ) VALUES (?, ?, ?, 'TENANT_ADMIN', 'TENANT_WIDE')
                    """,
                    invitationId, assignmentId, tenantId
            );
            jdbc.update(
                    """
                    UPDATE identity.invitations
                       SET status = 'CONSUMED', version = 1,
                           consumed_at = ?, updated_at = ?
                     WHERE id = ?
                    """,
                    Timestamp.from(consumedAt), Timestamp.from(consumedAt), invitationId
            );
        });
    }

    private String statusOfTenant(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT status FROM tenancy.tenants WHERE id = ?",
                String.class,
                tenantId
        );
    }
}
