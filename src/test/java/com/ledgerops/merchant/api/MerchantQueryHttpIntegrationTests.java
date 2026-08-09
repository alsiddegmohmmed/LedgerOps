package com.ledgerops.merchant.api;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class MerchantQueryHttpIntegrationTests {

    private static final String ISSUER = "https://issuer.example";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tenantMerchantReadReturnsDeterministicTenantScopedRows() throws Exception {
        UUID tenantId = insertTenant("Merchant Read Tenant");
        UUID first = insertMerchant(tenantId, "Alpha Merchant", "ACTIVE");
        UUID second = insertMerchant(tenantId, "Beta Merchant", "SUSPENDED");

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/merchants", tenantId)
                        .accept(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.TENANT_WIDE,
                                    Set.of(), Permission.MERCHANT_READ);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].merchantId").value(first.toString()))
                .andExpect(jsonPath("$[0].name").value("Alpha Merchant"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].merchantId").value(second.toString()))
                .andExpect(jsonPath("$[1].name").value("Beta Merchant"))
                .andExpect(jsonPath("$[1].version").value(0));

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/merchants/{merchantId}", tenantId, first)
                        .accept(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.TENANT_WIDE,
                                    Set.of(), Permission.MERCHANT_READ);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.merchantId").value(first.toString()));
    }

    @Test
    void merchantScopedReaderSeesOnlyAssignedMerchants() throws Exception {
        UUID tenantId = insertTenant("Scoped Merchant Read Tenant");
        UUID visible = insertMerchant(tenantId, "Visible Merchant", "ACTIVE");
        UUID hidden = insertMerchant(tenantId, "Hidden Merchant", "ACTIVE");

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/merchants", tenantId)
                        .accept(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.MERCHANT_SET,
                                    Set.of(visible), Permission.MERCHANT_READ);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].merchantId").value(visible.toString()))
                .andExpect(jsonPath("$[1]").doesNotExist());

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/merchants/{merchantId}", tenantId, hidden)
                        .with(request -> {
                            attachTenantContext(request, tenantId, ScopeMode.MERCHANT_SET,
                                    Set.of(visible), Permission.MERCHANT_READ);
                            return request;
                }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledgerops:problem:resource-not-found"));
    }

    @Test
    void merchantReadRequiresMerchantPermission() throws Exception {
        UUID tenantId = insertTenant("Denied Merchant Read Tenant");

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/merchants", tenantId)
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
                        "merchant-query-http-test"
                )
        );
        request.setAttribute(
                AuthorizedRequestContextRequest.principalAttribute(),
                new AuthenticatedPrincipal("HUMAN", ISSUER, "tenant-admin")
        );
    }

    private UUID insertTenant(String name) {
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
                name,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return tenantId;
    }

    private UUID insertMerchant(UUID tenantId, String name, String status) {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO merchant.merchants (
                    id, tenant_id, name, status, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 0, ?, ?)
                """,
                merchantId,
                tenantId,
                name,
                status,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return merchantId;
    }
}
