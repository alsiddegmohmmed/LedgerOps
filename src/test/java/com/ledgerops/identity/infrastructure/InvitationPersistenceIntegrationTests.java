package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.Invitation;
import com.ledgerops.identity.domain.InvitationId;
import com.ledgerops.identity.domain.InvitationRepository;
import com.ledgerops.identity.domain.InvitationTokenHash;
import com.ledgerops.identity.domain.MerchantScope;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantRole;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import com.ledgerops.identity.domain.TenantRoleAssignmentId;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class InvitationPersistenceIntegrationTests {

    @Autowired
    private InvitationRepository invitations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void persistsPendingInvitationWithProposedMerchantScopedRole() {
        UUID tenantId = insertTenant();
        UUID merchantId = insertMerchant(tenantId);
        TenantMembershipId membershipId = TenantMembershipId.newId();
        InvitationId invitationId = InvitationId.newId();
        InvitationTokenHash tokenHash = new InvitationTokenHash("a1".repeat(32));
        TenantRoleAssignment assignment = TenantRoleAssignment.merchantScoped(
                TenantRoleAssignmentId.newId(),
                tenantId,
                TenantRole.MERCHANT_ADMIN,
                MerchantScope.validated(tenantId, Set.of(merchantId), Map.of(merchantId, tenantId))
        );
        Invitation invitation = Invitation.create(
                invitationId,
                tenantId,
                " Admin@Example.com ",
                tokenHash,
                Set.of(assignment),
                Instant.now().minusSeconds(1)
        );

        Invitation saved = transactions.execute(status -> {
            insertInvitedMembership(tenantId, membershipId);
            return invitations.save(invitation, membershipId);
        });

        assertThat(saved.status().name()).isEqualTo("PENDING");
        Invitation loaded = invitations.findById(invitationId).orElseThrow();
        assertThat(loaded.intendedEmail()).isEqualTo("admin@example.com");
        assertThat(loaded.tokenHash()).isEqualTo(tokenHash);
        assertThat(loaded.proposedAssignments()).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo(assignment.id());
            assertThat(value.role()).isEqualTo(TenantRole.MERCHANT_ADMIN);
            assertThat(value.merchantScope().merchantIds()).containsExactly(merchantId);
        });
        assertThat(invitations.findPendingByTokenHash(tokenHash))
                .hasValueSatisfying(value -> assertThat(value.id()).isEqualTo(invitationId));
    }

    @Test
    void refusesToRewriteExistingInvitationGrantIntent() {
        UUID tenantId = insertTenant();
        UUID merchantId = insertMerchant(tenantId);
        TenantMembershipId membershipId = TenantMembershipId.newId();
        InvitationId invitationId = InvitationId.newId();
        Instant createdAt = Instant.now().minusSeconds(1);
        TenantRoleAssignment originalAssignment = TenantRoleAssignment.merchantScoped(
                TenantRoleAssignmentId.newId(),
                tenantId,
                TenantRole.MERCHANT_ADMIN,
                MerchantScope.validated(tenantId, Set.of(merchantId), Map.of(merchantId, tenantId))
        );
        Invitation original = Invitation.create(
                invitationId,
                tenantId,
                "person@example.com",
                new InvitationTokenHash("b2".repeat(32)),
                Set.of(originalAssignment),
                createdAt
        );
        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(tenantId, membershipId);
            invitations.save(original, membershipId);
        });

        TenantRoleAssignment replacementAssignment = TenantRoleAssignment.tenantWide(
                TenantRoleAssignmentId.newId(), tenantId, TenantRole.VIEWER
        );
        Invitation rewritten = Invitation.create(
                invitationId,
                tenantId,
                "person@example.com",
                original.tokenHash(),
                Set.of(replacementAssignment),
                createdAt
        );

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                invitations.save(rewritten, membershipId)
        ))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO tenancy.tenants (
                    id, name, default_currency, default_locale, status,
                    version, created_at, updated_at
                ) VALUES (?, ?, 'SAR', 'en-SA', 'ACTIVE', 0, ?, ?)
                """,
                tenantId,
                "Invitation Persistence Tenant " + tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return tenantId;
    }

    private UUID insertMerchant(UUID tenantId) {
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
                "Invitation Persistence Merchant " + merchantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return merchantId;
    }

    private void insertInvitedMembership(UUID tenantId, TenantMembershipId membershipId) {
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO identity.tenant_memberships (
                    id, application_user_id, tenant_id, status, version,
                    created_at, updated_at
                ) VALUES (?, NULL, ?, 'INVITED', 0, ?, ?)
                """,
                membershipId.value(),
                tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }
}
