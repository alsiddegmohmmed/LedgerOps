package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.InvitationRevocationCommand;
import com.ledgerops.identity.api.InvitationRevocationPort;
import com.ledgerops.identity.api.InvitationRevocationResult;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.domain.Invitation;
import com.ledgerops.identity.domain.InvitationId;
import com.ledgerops.identity.domain.InvitationRepository;
import com.ledgerops.identity.domain.InvitationTokenHash;
import com.ledgerops.identity.domain.MerchantScope;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.TenantMembershipId;
import com.ledgerops.identity.domain.TenantRole;
import com.ledgerops.identity.domain.TenantRoleAssignment;
import com.ledgerops.identity.domain.TenantRoleAssignmentId;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = {
        "ledgerops.identity.platform-admin.bootstrap-enabled=true",
        "ledgerops.identity.platform-admin.issuer=https://issuer.example",
        "ledgerops.identity.platform-admin.subject=platform-admin"
})
class InvitationRevocationIntegrationTests {

    private static final String ISSUER = "https://issuer.example";

    @Autowired
    private InvitationRevocationPort revocations;

    @Autowired
    private InvitationRepository invitations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void revokesPendingInvitationAndPersistsMembershipAuditAndOutboxAtomically() {
        UUID tenantId = insertTenant();
        TenantMembershipId membershipId = TenantMembershipId.newId();
        InvitationId invitationId = InvitationId.newId();
        UUID correlationId = UUID.randomUUID();
        Invitation invitation = Invitation.create(
                invitationId,
                tenantId,
                "invite@example.com",
                new InvitationTokenHash("a1".repeat(32)),
                Set.of(TenantRoleAssignment.tenantWide(
                        TenantRoleAssignmentId.newId(), tenantId, TenantRole.TENANT_ADMIN)),
                Instant.now()
        );

        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(tenantId, membershipId);
            invitations.save(invitation, membershipId);
        });

        InvitationRevocationResult result = revocations.revoke(command(
                tenantId,
                membershipId.value(),
                ScopeMode.TENANT_WIDE,
                Set.of(),
                correlationId
        ));

        assertThat(result.tenantId()).isEqualTo(tenantId);
        assertThat(result.membershipId()).isEqualTo(membershipId.value());
        assertThat(result.invitationId()).isEqualTo(invitationId.value());
        assertThat(result.membershipStatus()).isEqualTo("REVOKED");
        assertThat(result.invitationStatus()).isEqualTo("REVOKED");
        assertThat(result.membershipVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.tenant_memberships WHERE id = ?",
                String.class,
                membershipId.value()
        )).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.invitations WHERE id = ?",
                String.class,
                invitationId.value()
        )).isEqualTo("REVOKED");

        assertThat(jdbc.queryForMap(
                "SELECT action_type, reason, details FROM audit.audit_records "
                        + "WHERE target_id = ?",
                membershipId.value().toString()
        )).satisfies(row -> {
            assertThat(row.get("action_type")).isEqualTo(
                    "identity.membership.invitation-revoked");
            assertThat(row.get("reason")).isEqualTo("撤销邀请");
            assertThat(row.get("details").toString()).contains(invitationId.value().toString());
        });
        assertThat(jdbc.queryForMap(
                "SELECT deduplication_key, correlation_id, causation_id, payload "
                        + "FROM messaging.outbox WHERE aggregate_id = ?",
                membershipId.value()
        )).satisfies(row -> {
            assertThat(row.get("deduplication_key")).isEqualTo(
                    "identity-event:TenantMembership:" + membershipId.value() + ":1");
            assertThat(row.get("correlation_id")).isEqualTo(correlationId);
            assertThat(row.get("causation_id")).isEqualTo(invitationId.value());
            assertThat(row.get("payload").toString())
                    .contains("\"event\":\"REVOKED\"")
                    .contains("\"version\":1")
                    .contains(invitationId.value().toString());
        });
    }

    @Test
    void merchantScopedManagerCannotRevokeInvitationOutsideItsMerchantScope() {
        UUID tenantId = insertTenant();
        UUID merchantId = insertMerchant(tenantId);
        TenantMembershipId membershipId = TenantMembershipId.newId();
        InvitationId invitationId = InvitationId.newId();
        Invitation invitation = Invitation.create(
                invitationId,
                tenantId,
                "invite@example.com",
                new InvitationTokenHash("b2".repeat(32)),
                Set.of(TenantRoleAssignment.merchantScoped(
                        TenantRoleAssignmentId.newId(),
                        tenantId,
                        TenantRole.MERCHANT_ADMIN,
                        MerchantScope.validated(
                                tenantId,
                                Set.of(merchantId),
                                Map.of(merchantId, tenantId)
                        )
                )),
                Instant.now()
        );

        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(tenantId, membershipId);
            invitations.save(invitation, membershipId);
        });

        assertThatThrownBy(() -> revocations.revoke(command(
                tenantId,
                membershipId.value(),
                ScopeMode.MERCHANT_SET,
                Set.of(UUID.randomUUID()),
                UUID.randomUUID()
        ))).isExactlyInstanceOf(AuthorizationResourceNotFoundException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.tenant_memberships WHERE id = ?",
                String.class,
                membershipId.value()
        )).isEqualTo("INVITED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.invitations WHERE id = ?",
                String.class,
                invitationId.value()
        )).isEqualTo("PENDING");
    }

    private InvitationRevocationCommand command(
            UUID tenantId,
            UUID membershipId,
            ScopeMode scopeMode,
            Set<UUID> merchantIds,
            UUID correlationId
    ) {
        return new InvitationRevocationCommand(
                tenantId,
                membershipId,
                true,
                "撤销邀请",
                new AuthorizedRequestContext(
                        PrincipalType.HUMAN,
                        UUID.randomUUID(),
                        null,
                        tenantId,
                        scopeMode,
                        merchantIds,
                        Set.of(Permission.TENANT_MEMBERSHIP_MANAGE),
                        correlationId.toString()
                ),
                new AuthenticatedPrincipal("HUMAN", ISSUER, "revoker")
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
                ) VALUES (?, ?, 'SAR', 'en-SA', 'ACTIVE', 0, ?, ?)
                """,
                tenantId,
                "Invitation Revocation Tenant " + tenantId,
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
                "Invitation Revocation Merchant " + merchantId,
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
                    id, application_user_id, tenant_id, status, is_initial,
                    version, created_at, updated_at
                ) VALUES (?, NULL, ?, 'INVITED', false, 0, ?, ?)
                """,
                membershipId.value(),
                tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }
}
