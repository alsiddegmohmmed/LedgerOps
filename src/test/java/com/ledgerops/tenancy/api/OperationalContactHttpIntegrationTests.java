package com.ledgerops.tenancy.api;

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
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class OperationalContactHttpIntegrationTests {

    private static final String ISSUER = "https://issuer.example";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tenantAdminCanCreateReadAndVersionAnOperationalContact() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");
        UUID contactId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/tenants/{tenantId}/operational-contacts/{contactId}", tenantId, contactId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contactRequest("FINANCE@EXAMPLE.COM", true, "Add finance contact"))
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_CONFIGURE);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.contactId").value(contactId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.email").value("finance@example.com"))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/operational-contacts", tenantId)
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_READ);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contactId").value(contactId.toString()))
                .andExpect(jsonPath("$[0].version").value(1));

        mockMvc.perform(put("/api/v1/tenants/{tenantId}/operational-contacts/{contactId}", tenantId, contactId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contactRequest("finance@example.com", false, "Deactivate finance contact"))
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_CONFIGURE);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/operational-contacts/{contactId}", tenantId, contactId)
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_READ);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.active").value(false));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenancy.operational_contacts WHERE tenant_id = ? AND contact_id = ?",
                Integer.class,
                tenantId,
                contactId
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM audit.audit_records WHERE tenant_id = ? "
                        + "AND action_type = 'tenant.operational-contact.changed' "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                String.class,
                tenantId
        )).isEqualTo("Deactivate finance contact");
    }

    @Test
    void contactUpdateRequiresTenantConfigurePermission() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");

        mockMvc.perform(put("/api/v1/tenants/{tenantId}/operational-contacts/{contactId}", tenantId, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contactRequest("ops@example.com", true, "Add operations contact"))
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_READ);
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    }

    @Test
    void contactChangeRequiresConfirmationAndReason() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");

        mockMvc.perform(put("/api/v1/tenants/{tenantId}/operational-contacts/{contactId}", tenantId, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contactRequest("ops@example.com", true, "")
                                .replace("\"confirmation\": true", "\"confirmation\": false"))
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_CONFIGURE);
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPERATIONAL_CONTACT_VALIDATION"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenancy.operational_contacts WHERE tenant_id = ?",
                Integer.class,
                tenantId
        )).isZero();
    }

    @Test
    void readingMissingContactReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/operational-contacts/{contactId}", tenantId, UUID.randomUUID())
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_READ);
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OPERATIONAL_CONTACT_NOT_FOUND"));
    }

    private void attachTenantContext(
            jakarta.servlet.http.HttpServletRequest request,
            UUID tenantId,
            Permission permission
    ) {
        request.setAttribute(
                AuthorizedRequestContext.class.getName(),
                new AuthorizedRequestContext(
                        PrincipalType.HUMAN,
                        UUID.randomUUID(),
                        null,
                        tenantId,
                        ScopeMode.TENANT_WIDE,
                        Set.of(),
                        Set.of(permission),
                        "operational-contact-http-test"
                )
        );
        request.setAttribute(
                AuthorizedRequestContextRequest.principalAttribute(),
                new AuthenticatedPrincipal("HUMAN", ISSUER, "tenant-admin")
        );
    }

    private String contactRequest(String email, boolean active, String reason) {
        return """
                {
                  "displayName": "Finance Team",
                  "email": "%s",
                  "purpose": "settlement",
                  "active": %s,
                  "confirmation": true,
                  "reason": "%s"
                }
                """.formatted(email, active, reason);
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
                "HTTP Contact Tenant " + tenantId,
                status,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return tenantId;
    }
}
