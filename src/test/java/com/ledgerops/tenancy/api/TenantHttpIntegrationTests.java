package com.ledgerops.tenancy.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class TenantHttpIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsReadsAndTransitionsTenant() throws Exception {
        MvcResult creation = mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("HTTP Lifecycle Payments")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("HTTP Lifecycle Payments"))
                .andExpect(jsonPath("$.defaultCurrency").value("SAR"))
                .andExpect(jsonPath("$.defaultLocale").value("en-SA"))
                .andExpect(jsonPath("$.status").value("PENDING_ACTIVATION"))
                .andReturn();

        String location = creation.getResponse().getHeader("Location");

        mockMvc.perform(post(location + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post(location + "/suspend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    void returnsProblemDetailForDuplicateName() throws Exception {
        String request = validRequest("HTTP Duplicate Payments");

        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
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
                                  "defaultLocale": "en-SA"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("urn:ledgerops:problem:tenant-request-validation"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.defaultCurrency").exists());
    }

    @Test
    void returnsProblemDetailForUnknownTenant() throws Exception {
        mockMvc.perform(get(
                        "/api/v1/tenants/00000000-0000-0000-0000-000000000001"
                ))
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
                                  "defaultLocale": "en-SA"
                                }
                                """))
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
                        .content(validRequest("HTTP Invalid Transition Payments")))
                .andExpect(status().isCreated())
                .andReturn();

        String location = creation.getResponse().getHeader("Location");

        mockMvc.perform(post(location + "/suspend"))
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
                  "defaultLocale": "en-SA"
                }
                """.formatted(name);
    }
}
