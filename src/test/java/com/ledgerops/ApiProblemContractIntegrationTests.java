package com.ledgerops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class ApiProblemContractIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointProvidesARelease01SmokeCheck() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestCorrelationFilter.CORRELATION_HEADER))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unknownRouteUsesSafeCorrelatedProblemDetails() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/not-a-resource"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists(RequestCorrelationFilter.CORRELATION_HEADER))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.type")
                        .value("urn:ledgerops:problem:api-resource-not-found"))
                .andExpect(jsonPath("$.code").value("API_RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.effect").value("No resource was read or changed."))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.nextAction").isNotEmpty())
                .andReturn();

        assertHeaderMatchesBody(result);
        assertSafeBody(result);
    }

    @Test
    void unsupportedMethodUsesTheSameStableProblemShape() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/v1/tenants"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("HTTP_METHOD_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.effect").isNotEmpty())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.nextAction").isNotEmpty())
                .andReturn();

        assertHeaderMatchesBody(result);
        assertSafeBody(result);
    }

    @Test
    void unsupportedMediaTypeUsesTheSameStableProblemShape() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MEDIA_TYPE_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.effect").isNotEmpty())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.nextAction").isNotEmpty())
                .andReturn();

        assertHeaderMatchesBody(result);
        assertSafeBody(result);
    }

    private void assertHeaderMatchesBody(MvcResult result) throws Exception {
        String correlationId = result.getResponse().getHeader(
                RequestCorrelationFilter.CORRELATION_HEADER
        );
        assertNotNull(correlationId);
        assertTrue(result.getResponse().getContentAsString().contains(correlationId));
    }

    private void assertSafeBody(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString().toLowerCase();
        assertFalse(body.contains("exception"));
        assertFalse(body.contains("stacktrace"));
        assertFalse(body.contains("select "));
        assertFalse(body.contains("jdbc"));
    }
}
