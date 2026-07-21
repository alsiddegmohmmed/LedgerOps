package com.ledgerops.observability;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ledgerops.messaging.publisher.enabled=false",
        "ledgerops.observability.kafka-lag.enabled=false",
        "ledgerops.provider.command-consumer.enabled=false",
        "ledgerops.provider.execution.enabled=false",
        "ledgerops.payment.result-consumer.enabled=false",
        "ledgerops.provider.webhook.enabled=false",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.prometheus.metrics.export.enabled=true"
})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class PrometheusEndpointIntegrationTests {

    @Autowired MockMvc mvc;

    @Test
    void prometheusEndpointExposesTheZeroTargetFinancialSafetyCounter()
            throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "ledgerops_duplicate_financial_effect_total")));
    }
}
