package com.ledgerops.administration.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class AdministrationHttpIntegrationTests {

    private static final String ISSUER = "https://issuer.example";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void activationRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/{tenantId}/activate", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void activationRequiresPlatformAdminAuthority() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/activate", tenantId)
                        .requestAttr(
                                AuthorizedRequestContextRequest.principalAttribute(),
                                new AuthenticatedPrincipal("HUMAN", ISSUER, "not-platform-admin")
                        ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

        assertThatStatus(tenantId, "PENDING_ACTIVATION");
    }

    @Test
    void platformAdminActivationUsesAdministrationPrerequisiteChecks() throws Exception {
        UUID tenantId = insertTenant();
        insertActiveMerchant(tenantId);

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/activate", tenantId)
                        .requestAttr(
                                AuthorizedRequestContextRequest.principalAttribute(),
                                platformAdmin()
                        ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("TENANT_ACTIVATION_PREREQUISITES_NOT_SATISFIED"))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.initialTenantAdminActive").value(false))
                .andExpect(jsonPath("$.activeMerchantExists").value(true));

        assertThatStatus(tenantId, "PENDING_ACTIVATION");
    }

    @Test
    void platformAdminActivationMapsUnknownTenantToNotFound() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/activate", tenantId)
                        .requestAttr(
                                AuthorizedRequestContextRequest.principalAttribute(),
                                platformAdmin()
                        ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
    }

    @Test
    void onboardingRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingRequest("HTTP Unauthenticated Onboarding")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void platformAdminOnboardingCreatesTheCompleteInitialTenantBoundary() throws Exception {
        String name = "HTTP Complete Onboarding " + UUID.randomUUID();

        MvcResult result = mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingRequest(name))
                        .requestAttr(
                                AuthorizedRequestContextRequest.principalAttribute(),
                                platformAdmin()
                        ))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.tenantId").isNotEmpty())
                .andExpect(jsonPath("$.merchantId").isNotEmpty())
                .andExpect(jsonPath("$.membershipId").isNotEmpty())
                .andExpect(jsonPath("$.invitationId").isNotEmpty())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        UUID tenantId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        assertThatStatus(tenantId, "PENDING_ACTIVATION");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM merchant.merchants WHERE tenant_id = ? AND status = 'ACTIVE'",
                Integer.class,
                tenantId
        )).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_memberships WHERE tenant_id = ? AND status = 'INVITED'",
                Integer.class,
                tenantId
        )).isEqualTo(1);
    }

    @Test
    void platformAdminCanSuspendAndArchiveThroughAdministration() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/suspend", tenantId)
                        .requestAttr(
                                AuthorizedRequestContextRequest.principalAttribute(),
                                platformAdmin()
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.value").value(tenantId.toString()))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/archive", tenantId)
                        .requestAttr(
                                AuthorizedRequestContextRequest.principalAttribute(),
                                platformAdmin()
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.value").value(tenantId.toString()))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        assertThatStatus(tenantId, "ARCHIVED");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_records "
                        + "WHERE target_id = ? AND action_type IN ('tenant.suspended', 'tenant.archived')",
                Integer.class,
                tenantId.toString()
        )).isEqualTo(2);
    }

    @Test
    void lifecycleActionsRejectNonPlatformActors() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");
        AuthenticatedPrincipal otherActor =
                new AuthenticatedPrincipal("HUMAN", ISSUER, "not-platform-admin");

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/suspend", tenantId)
                        .requestAttr(
                                AuthorizedRequestContextRequest.principalAttribute(),
                                otherActor
                        ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));

        assertThatStatus(tenantId, "ACTIVE");
    }

    private AuthenticatedPrincipal platformAdmin() {
        return new AuthenticatedPrincipal("HUMAN", ISSUER, "platform-admin");
    }

    private String onboardingRequest(String name) {
        String tokenHash = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .repeat(2);
        return """
                {
                  "name": "%s",
                  "defaultCurrency": "SAR",
                  "defaultLocale": "en-SA",
                  "merchantName": "%s Merchant",
                  "initialAdminEmail": "admin@example.com",
                  "invitationTokenHash": "%s"
                }
                """.formatted(name, name, tokenHash);
    }

    private UUID insertTenant() {
        return insertTenant("PENDING_ACTIVATION");
    }

    private UUID insertTenant(String status) {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO tenancy.tenants (
                    id, name, default_currency, default_locale, status,
                    version, created_at, updated_at
                ) VALUES (?, ?, 'SAR', 'en-SA', ?, 0, ?, ?)
                """,
                tenantId,
                "HTTP Administration Tenant " + tenantId,
                status,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return tenantId;
    }

    private void insertActiveMerchant(UUID tenantId) {
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO merchant.merchants (
                    id, tenant_id, name, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """,
                UUID.randomUUID(),
                tenantId,
                "HTTP Administration Merchant " + tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private void assertThatStatus(UUID tenantId, String expected) {
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM tenancy.tenants WHERE id = ?",
                String.class,
                tenantId
        )).isEqualTo(expected);
    }
}
