package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.api.TenantActivationReadPort;
import com.ledgerops.identity.api.TenantActivationReadiness;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantActivationReadIntegrationTests {

    @Autowired
    private TenantActivationReadPort readiness;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void reportsIncompleteReadinessBeforeInitialInvitationIsAccepted() {
        UUID tenantId = insertTenant();

        TenantActivationReadiness result = readiness.assess(tenantId);

        assertThat(result.initialTenantAdminActive()).isFalse();
        assertThat(result.onboardingConsistent()).isFalse();
    }

    @Test
    void reportsActiveInitialAdminAndConsistentOnboardingAfterAcceptance() {
        UUID tenantId = insertTenant();
        UUID userId = insertApplicationUser();
        UUID membershipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(10);
        Instant consumedAt = Instant.now();

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
                    "7b".repeat(32),
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

        TenantActivationReadiness result = readiness.assess(tenantId);

        assertThat(result.initialTenantAdminActive()).isTrue();
        assertThat(result.onboardingConsistent()).isTrue();
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
                "Activation Read Tenant " + tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return tenantId;
    }

    private UUID insertApplicationUser() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO identity.application_users (
                    id, issuer, subject, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                userId,
                "activation-issuer-" + userId,
                "activation-subject-" + userId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return userId;
    }
}
