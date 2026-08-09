package com.ledgerops.administration.membership.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = {
        "ledgerops.identity.platform-admin.bootstrap-enabled=true",
        "ledgerops.identity.platform-admin.issuer=https://issuer.example",
        "ledgerops.identity.platform-admin.subject=platform-admin"
})
class InvitationRevocationHttpIntegrationTests {

    private static final String ISSUER = "https://issuer.example";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InvitationRepository invitations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void tenantWideManagerCanRevokePendingInvitation() throws Exception {
        UUID tenantId = insertTenant();
        TenantMembershipId membershipId = TenantMembershipId.newId();
        Invitation invitation = invitation(tenantId, membershipId, TenantRole.TENANT_ADMIN);

        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(tenantId, membershipId);
            invitations.save(invitation, membershipId);
        });

        mockMvc.perform(post(
                        "/api/v1/tenants/{tenantId}/memberships/{membershipId}/invitation/revoke",
                        tenantId,
                        membershipId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":true,\"reason\":\"No longer required\"}")
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.TENANT_WIDE, Set.of(),
                                    Permission.TENANT_MEMBERSHIP_MANAGE);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.membershipId").value(membershipId.value().toString()))
                .andExpect(jsonPath("$.invitationId").value(invitation.id().value().toString()))
                .andExpect(jsonPath("$.membershipStatus").value("REVOKED"))
                .andExpect(jsonPath("$.invitationStatus").value("REVOKED"))
                .andExpect(jsonPath("$.membershipVersion").value(1));
    }

    @Test
    void merchantScopedManagerCannotDiscoverInvitationOutsideMerchantScope() throws Exception {
        UUID tenantId = insertTenant();
        UUID merchantId = insertMerchant(tenantId);
        TenantMembershipId membershipId = TenantMembershipId.newId();
        Invitation invitation = invitation(tenantId, membershipId, merchantId);

        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(tenantId, membershipId);
            invitations.save(invitation, membershipId);
        });

        mockMvc.perform(post(
                        "/api/v1/tenants/{tenantId}/memberships/{membershipId}/invitation/revoke",
                        tenantId,
                        membershipId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":true,\"reason\":\"Outside scope\"}")
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.MERCHANT_SET,
                                    Set.of(UUID.randomUUID()), Permission.TENANT_MEMBERSHIP_MANAGE);
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type")
                        .value("urn:ledgerops:problem:resource-not-found"));
    }

    @Test
    void invalidConfirmationDoesNotChangeInvitation() throws Exception {
        UUID tenantId = insertTenant();
        TenantMembershipId membershipId = TenantMembershipId.newId();
        Invitation invitation = invitation(tenantId, membershipId, TenantRole.VIEWER);

        transactions.executeWithoutResult(status -> {
            insertInvitedMembership(tenantId, membershipId);
            invitations.save(invitation, membershipId);
        });

        mockMvc.perform(post(
                        "/api/v1/tenants/{tenantId}/memberships/{membershipId}/invitation/revoke",
                        tenantId,
                        membershipId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":false,\"reason\":\"Not confirmed\"}")
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.TENANT_WIDE, Set.of(),
                                    Permission.TENANT_MEMBERSHIP_MANAGE);
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("urn:ledgerops:problem:invitation-revocation-validation"));

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM identity.invitations WHERE id = ?",
                String.class,
                invitation.id().value()
        )).isEqualTo("PENDING");
    }

    private Invitation invitation(UUID tenantId, TenantMembershipId membershipId, TenantRole role) {
        return Invitation.create(
                InvitationId.newId(),
                tenantId,
                "invite@example.com",
                new InvitationTokenHash(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", "")),
                Set.of(TenantRoleAssignment.tenantWide(
                        TenantRoleAssignmentId.newId(), tenantId, role)),
                Instant.now()
        );
    }

    private Invitation invitation(UUID tenantId, TenantMembershipId membershipId, UUID merchantId) {
        return Invitation.create(
                InvitationId.newId(),
                tenantId,
                "invite@example.com",
                new InvitationTokenHash(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", "")),
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
    }

    private void attachTenantContext(
            jakarta.servlet.http.HttpServletRequest request,
            UUID tenantId,
            ScopeMode scopeMode,
            Set<UUID> merchantIds,
            Permission permission
    ) {
        request.setAttribute(
                AuthorizedRequestContext.class.getName(),
                new AuthorizedRequestContext(
                        PrincipalType.HUMAN,
                        UUID.randomUUID(),
                        null,
                        tenantId,
                        scopeMode,
                        merchantIds,
                        Set.of(permission),
                        "invitation-revocation-http-test"
                )
        );
        request.setAttribute(
                AuthorizedRequestContextRequest.principalAttribute(),
                new AuthenticatedPrincipal("HUMAN", ISSUER, "tenant-admin")
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
                "HTTP Invitation Revocation Tenant " + tenantId,
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
                "HTTP Invitation Revocation Merchant " + merchantId,
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
