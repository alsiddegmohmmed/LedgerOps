package com.ledgerops.tenancy.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = {
        "ledgerops.identity.platform-admin.bootstrap-enabled=true",
        "ledgerops.identity.platform-admin.issuer=https://issuer.example",
        "ledgerops.identity.platform-admin.subject=platform-admin"
})
class TenantHttpIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsAndReadsPendingTenant() throws Exception {
        MvcResult creation = mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("HTTP Lifecycle Payments"))
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), platformAdmin()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.tenantId").isNotEmpty())
                .andExpect(jsonPath("$.merchantId").isNotEmpty())
                .andExpect(jsonPath("$.membershipId").isNotEmpty())
                .andExpect(jsonPath("$.invitationId").isNotEmpty())
                .andReturn();

        String location = creation.getResponse().getHeader("Location");

        mockMvc.perform(post(location + "/activate"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        UUID tenantId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        mockMvc.perform(get(location)
                        .requestAttr(AuthorizedRequestContext.class.getName(), readContext(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_ACTIVATION"));
    }

    @Test
    void returnsProblemDetailForDuplicateName() throws Exception {
        String request = validRequest("HTTP Duplicate Payments");

        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), platformAdmin()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), platformAdmin()))
                .andExpect(status().isConflict())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.type")
                        .value("urn:ledgerops:problem:tenant-name-conflict"))
                .andExpect(jsonPath("$.title").value("Tenant name conflict"))
                .andExpect(jsonPath("$.code").value("TENANT_NAME_CONFLICT"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.effect").value("No tenant was created."))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.nextAction").isNotEmpty())
                .andExpect(jsonPath("$.tenantName").value("HTTP Duplicate Payments"));
    }

    @Test
    void returnsProblemDetailForInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "defaultCurrency": "sar",
                                  "defaultLocale": "en-SA",
                                  "merchantName": "HTTP Invalid Merchant",
                                  "initialAdminEmail": "admin@example.com",
                                  "invitationTokenHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                }
                                """ )
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), platformAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("urn:ledgerops:problem:tenant-request-validation"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.defaultCurrency").exists());
    }

    @Test
    void returnsProblemDetailForUnknownTenant() throws Exception {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        mockMvc.perform(get("/api/v1/tenants/" + tenantId)
                        .requestAttr(AuthorizedRequestContext.class.getName(), readContext(tenantId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type")
                        .value("urn:ledgerops:problem:tenant-not-found"));
    }

    @Test
    void returnsProblemDetailForUnsupportedCurrencyAndMalformedTenantId()
            throws Exception {
        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Unsupported Currency Payments",
                                  "defaultCurrency": "ZZZ",
                                  "defaultLocale": "en-SA",
                                  "merchantName": "HTTP Unsupported Merchant",
                                  "initialAdminEmail": "admin@example.com",
                                  "invitationTokenHash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                }
                                """
                        )
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), platformAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_REQUEST"));

        mockMvc.perform(get("/api/v1/tenants/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_REQUEST"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void returnsProblemDetailForInvalidLifecycleTransition() throws Exception {
        MvcResult creation = mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("HTTP Invalid Transition Payments"))
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), platformAdmin()))
                .andExpect(status().isCreated())
                .andReturn();

        String location = creation.getResponse().getHeader("Location");

        mockMvc.perform(post(location + "/suspend")
                        .requestAttr(AuthorizedRequestContextRequest.principalAttribute(), platformAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("urn:ledgerops:problem:invalid-tenant-transition"))
                .andExpect(jsonPath("$.targetStatus").value("SUSPENDED"));
    }

    private String validRequest(String name) {
        return """
                {
                  "name": "%s",
                  "defaultCurrency": "SAR",
                  "defaultLocale": "en-SA",
                  "merchantName": "%s Merchant",
                  "initialAdminEmail": "admin-%s@example.com",
                  "invitationTokenHash": "%s"
                }
                """.formatted(
                name,
                name.replace(" ", "-"),
                name.replace(" ", "-").toLowerCase(),
                UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "")
                        .repeat(2)
        );
    }

    private AuthenticatedPrincipal platformAdmin() {
        return new AuthenticatedPrincipal(
                "HUMAN", "https://issuer.example", "platform-admin");
    }

    private AuthorizedRequestContext readContext(UUID tenantId) {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                UUID.randomUUID(),
                null,
                tenantId,
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(Permission.TENANT_READ),
                "test-correlation"
        );
    }
}
