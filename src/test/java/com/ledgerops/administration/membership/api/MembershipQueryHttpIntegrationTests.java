package com.ledgerops.administration.membership.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class MembershipQueryHttpIntegrationTests {

    private static final String ISSUER = "https://issuer.example";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void tenantAdminCanReadMembershipAndSafeInvitationSummary() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID activeMembershipId = UUID.randomUUID();
        UUID activeUserId = UUID.randomUUID();
        UUID activeAssignmentId = UUID.randomUUID();
        UUID invitedMembershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UUID invitationAssignmentId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            insertTenant(tenantId);
            insertApplicationUser(activeUserId);
            insertActiveMembership(tenantId, activeMembershipId, activeUserId);
            insertTenantWideAssignment(activeMembershipId, activeAssignmentId, "TENANT_ADMIN");
            insertInvitedMembership(tenantId, invitedMembershipId);
            insertInvitation(
                    tenantId,
                    invitedMembershipId,
                    invitationId,
                    "invite@example.com",
                    Instant.now().minusSeconds(30)
            );
            insertInvitationGrant(
                    tenantId,
                    invitationId,
                    invitationAssignmentId,
                    "VIEWER",
                    "TENANT_WIDE"
            );
        });

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/memberships", tenantId)
                        .accept(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.TENANT_WIDE,
                                    Set.of(), Permission.TENANT_MEMBERSHIP_MANAGE);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.membershipId == '%s')].identityLinked"
                        .formatted(activeMembershipId)).value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.membershipId == '%s')].roleAssignments[0].role"
                        .formatted(activeMembershipId)).value(org.hamcrest.Matchers.hasItem("TENANT_ADMIN")))
                .andExpect(jsonPath("$[?(@.membershipId == '%s')].invitation.intendedEmail"
                        .formatted(invitedMembershipId)).value(org.hamcrest.Matchers.hasItem("invite@example.com")))
                .andExpect(jsonPath("$[?(@.membershipId == '%s')].invitation.status"
                        .formatted(invitedMembershipId)).value(org.hamcrest.Matchers.hasItem("PENDING")))
                .andExpect(jsonPath("$[?(@.membershipId == '%s')].invitation.tokenHash"
                        .formatted(invitedMembershipId)).doesNotExist());

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/memberships/{membershipId}",
                        tenantId, activeMembershipId)
                        .accept(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.TENANT_WIDE,
                                    Set.of(), Permission.TENANT_MEMBERSHIP_MANAGE);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipId").value(activeMembershipId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.identityLinked").value(true));
    }

    @Test
    void merchantScopedManagerSeesOnlyMembershipsWithVisibleMerchantAssignments() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID visibleMerchantId = UUID.randomUUID();
        UUID visibleMembershipId = UUID.randomUUID();
        UUID visibleUserId = UUID.randomUUID();
        UUID visibleAssignmentId = UUID.randomUUID();
        UUID hiddenMembershipId = UUID.randomUUID();
        UUID hiddenUserId = UUID.randomUUID();
        UUID hiddenAssignmentId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            insertTenant(tenantId);
            insertMerchant(tenantId, visibleMerchantId);
            insertApplicationUser(visibleUserId);
            insertApplicationUser(hiddenUserId);
            insertActiveMembership(tenantId, visibleMembershipId, visibleUserId);
            insertMerchantScopedAssignment(
                    visibleMembershipId,
                    visibleAssignmentId,
                    "MERCHANT_ADMIN",
                    visibleMerchantId
            );
            insertActiveMembership(tenantId, hiddenMembershipId, hiddenUserId);
            insertTenantWideAssignment(hiddenMembershipId, hiddenAssignmentId, "TENANT_ADMIN");
        });

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/memberships", tenantId)
                        .accept(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.MERCHANT_SET,
                                    Set.of(visibleMerchantId), Permission.TENANT_MEMBERSHIP_MANAGE);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].membershipId").value(visibleMembershipId.toString()))
                .andExpect(jsonPath("$[0].roleAssignments[0].scopeMode").value("MERCHANT_SET"))
                .andExpect(jsonPath("$[0].roleAssignments[0].merchantIds[0]")
                        .value(visibleMerchantId.toString()))
                .andExpect(jsonPath("$[1]").doesNotExist());

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/memberships/{membershipId}",
                        tenantId, hiddenMembershipId)
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.MERCHANT_SET,
                                    Set.of(visibleMerchantId), Permission.TENANT_MEMBERSHIP_MANAGE);
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type")
                        .value("urn:ledgerops:problem:resource-not-found"));
    }

    @Test
    void membershipReadRequiresMembershipManagementPermission() throws Exception {
        UUID tenantId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> insertTenant(tenantId));

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/memberships", tenantId)
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.TENANT_WIDE,
                                    Set.of(), Permission.TENANT_READ);
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
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
                        "membership-query-http-test"
                )
        );
        request.setAttribute(
                AuthorizedRequestContextRequest.principalAttribute(),
                new AuthenticatedPrincipal("HUMAN", ISSUER, "tenant-admin")
        );
    }

    private void insertTenant(UUID tenantId) {
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO tenancy.tenants (
                    id, name, default_currency, default_locale, status,
                    version, created_at, updated_at
                ) VALUES (?, ?, 'SAR', 'en-SA', 'ACTIVE', 0, ?, ?)
                """,
                tenantId,
                "HTTP Membership Tenant " + tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private void insertMerchant(UUID tenantId, UUID merchantId) {
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO merchant.merchants (
                    id, tenant_id, name, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                merchantId,
                tenantId,
                "HTTP Membership Merchant " + merchantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private void insertApplicationUser(UUID userId) {
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO identity.application_users (
                    id, issuer, subject, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                userId,
                ISSUER,
                "membership-user-" + userId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private void insertActiveMembership(UUID tenantId, UUID membershipId, UUID userId) {
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO identity.tenant_memberships (
                    id, application_user_id, tenant_id, status, is_initial,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', false, 0, ?, ?)
                """,
                membershipId,
                userId,
                tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private void insertInvitedMembership(UUID tenantId, UUID membershipId) {
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO identity.tenant_memberships (
                    id, application_user_id, tenant_id, status, is_initial,
                    version, created_at, updated_at
                ) VALUES (?, NULL, ?, 'INVITED', false, 0, ?, ?)
                """,
                membershipId,
                tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private void insertTenantWideAssignment(UUID membershipId, UUID assignmentId, String role) {
        jdbc.update(
                """
                INSERT INTO identity.tenant_role_assignments (
                    id, membership_id, role, scope_mode
                ) VALUES (?, ?, ?, 'TENANT_WIDE')
                """,
                assignmentId,
                membershipId,
                role
        );
    }

    private void insertMerchantScopedAssignment(
            UUID membershipId,
            UUID assignmentId,
            String role,
            UUID merchantId
    ) {
        jdbc.update(
                """
                INSERT INTO identity.tenant_role_assignments (
                    id, membership_id, role, scope_mode
                ) VALUES (?, ?, ?, 'MERCHANT_SET')
                """,
                assignmentId,
                membershipId,
                role
        );
        jdbc.update(
                """
                INSERT INTO identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id)
                VALUES (?, ?)
                """,
                assignmentId,
                merchantId
        );
    }

    private void insertInvitation(
            UUID tenantId,
            UUID membershipId,
            UUID invitationId,
            String email,
            Instant createdAt
    ) {
        jdbc.update(
                """
                INSERT INTO identity.invitations (
                    id, tenant_id, membership_id, intended_email, token_hash,
                    status, version, created_at, expires_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                """,
                invitationId,
                tenantId,
                membershipId,
                email,
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt.plus(Duration.ofDays(7))),
                Timestamp.from(createdAt)
        );
    }

    private void insertInvitationGrant(
            UUID tenantId,
            UUID invitationId,
            UUID assignmentId,
            String role,
            String scopeMode
    ) {
        jdbc.update(
                """
                INSERT INTO identity.invitation_grants (
                    invitation_id, assignment_id, tenant_id, role, scope_mode
                ) VALUES (?, ?, ?, ?, ?)
                """,
                invitationId,
                assignmentId,
                tenantId,
                role,
                scopeMode
        );
    }
}
