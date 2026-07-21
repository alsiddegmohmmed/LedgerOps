package com.ledgerops.provider.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderOperationalMetricsTests {

    @Test
    void backlogGaugesIncludeClaimedRetryAndAllNonterminalRecoveryStates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SAFE_TO_RESUBMIT")) return 2;
            if (sql.contains("recovery_attempts")) return 3;
            return 4;
        });
        var registry = new SimpleMeterRegistry();
        var metrics = new ProviderOperationalMetrics(
                jdbc, CircuitBreaker.ofDefaults("test"), registry
        );

        metrics.refresh();

        assertEquals(2.0, registry.get("ledgerops.provider.retry.backlog").gauge().value());
        assertEquals(3.0, registry.get("ledgerops.provider.recovery.backlog").gauge().value());
        assertEquals(4.0, registry.get("ledgerops.webhook.backlog").gauge().value());
        assertTrue(registry.getMeters().stream().noneMatch(meter -> meter.getId().getTags()
                .stream().anyMatch(tag -> tag.getKey().contains("tenant"))));
    }
}
