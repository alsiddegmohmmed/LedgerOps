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
class TenantConfigurationHttpIntegrationTests {

    private static final String ISSUER = "https://issuer.example";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tenantAdminCanUpdateAndReadTheCurrentVersionedConfiguration() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");

        mockMvc.perform(put("/api/v1/tenants/{tenantId}/configuration", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configurationRequest())
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_CONFIGURE);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.allowedCurrencies").isArray())
                .andExpect(jsonPath("$.allowedCurrencies").value(
                        org.hamcrest.Matchers.containsInAnyOrder("SAR", "USD")))
                .andExpect(jsonPath("$.defaultLocale").value("en-SA"))
                .andExpect(jsonPath("$.timezone").value("Asia/Riyadh"))
                .andExpect(jsonPath("$.displaySettings.dateFormat").value("yyyy-MM-dd"));

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/configuration", tenantId)
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_READ);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.displaySettings.dateFormat").value("yyyy-MM-dd"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenancy.tenant_configurations WHERE tenant_id = ?",
                Integer.class,
                tenantId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_records WHERE tenant_id = ? "
                        + "AND action_type = 'tenant.configuration.changed'",
                Integer.class,
                tenantId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM audit.audit_records WHERE tenant_id = ? "
                        + "AND action_type = 'tenant.configuration.changed'",
                String.class,
                tenantId
        )).isEqualTo("Update Tenant display settings during HTTP coverage");
    }

    @Test
    void configurationUpdateRequiresTenantConfigurePermission() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");

        mockMvc.perform(put("/api/v1/tenants/{tenantId}/configuration", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configurationRequest())
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_READ);
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"));
    }

    @Test
    void rejectsInvalidTimezoneWithoutAppendingAConfigurationVersion() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");

        mockMvc.perform(put("/api/v1/tenants/{tenantId}/configuration", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configurationRequest().replace("Asia/Riyadh", "Not/AZone"))
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_CONFIGURE);
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_TENANT_CONFIGURATION_REQUEST"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenancy.tenant_configurations WHERE tenant_id = ?",
                Integer.class,
                tenantId
        )).isZero();
    }

    @Test
    void currentConfigurationReturnsNotFoundBeforeTheFirstVersion() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/configuration", tenantId)
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_READ);
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("TENANT_CONFIGURATION_NOT_FOUND"));
    }

    @Test
    void configurationChangeRequiresConfirmationAndReason() throws Exception {
        UUID tenantId = insertTenant("ACTIVE");

        mockMvc.perform(put("/api/v1/tenants/{tenantId}/configuration", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configurationRequest()
                                .replace("\"confirmation\": true", "\"confirmation\": false")
                                .replace("\"reason\": \"Update Tenant display settings during HTTP coverage\"",
                                        "\"reason\": \"\""))
                        .with(request -> {
                            attachTenantContext(request, tenantId, Permission.TENANT_CONFIGURE);
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("TENANT_CONFIGURATION_VALIDATION"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenancy.tenant_configurations WHERE tenant_id = ?",
                Integer.class,
                tenantId
        )).isZero();
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
                        "configuration-http-test"
                )
        );
        request.setAttribute(
                AuthorizedRequestContextRequest.principalAttribute(),
                new AuthenticatedPrincipal("HUMAN", ISSUER, "tenant-admin")
        );
    }

    private String configurationRequest() {
        return """
                {
                  "allowedCurrencies": ["SAR", "USD"],
                  "defaultLocale": "en-SA",
                  "timezone": "Asia/Riyadh",
                  "displaySettings": {"dateFormat": "yyyy-MM-dd"},
                  "confirmation": true,
                  "reason": "Update Tenant display settings during HTTP coverage"
                }
                """;
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
                "HTTP Configuration Tenant " + tenantId,
                status,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return tenantId;
    }
}
