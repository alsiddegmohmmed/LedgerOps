package com.ledgerops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class OperationalLoggingIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requestLogsCarryCorrelationWithoutPayloadData(CapturedOutput output) throws Exception {
        String payloadMarker = "do-not-log-release-01-payload";
        MvcResult result = mockMvc.perform(post("/api/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "defaultCurrency": "SAR",
                                  "defaultLocale": "en-SA"
                                }
                                """.formatted(payloadMarker)))
                .andExpect(status().isCreated())
                .andReturn();

        String correlationId = result.getResponse().getHeader(
                RequestCorrelationFilter.CORRELATION_HEADER
        );
        assertNotNull(correlationId);
        assertTrue(output.getOut().contains("correlationId=" + correlationId));
        assertTrue(output.getOut().contains("Tenant created tenantId="));
        assertFalse(output.getOut().contains(payloadMarker));
    }
}
