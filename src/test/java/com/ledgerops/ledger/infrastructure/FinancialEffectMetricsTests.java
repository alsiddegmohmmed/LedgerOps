package com.ledgerops.ledger.infrastructure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinancialEffectMetricsTests {

    @Test
    void detectorExposesNonzeroDuplicateSourceCount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
        var registry = new SimpleMeterRegistry();
        var metrics = new FinancialEffectMetrics(jdbc, registry);

        metrics.refresh();

        assertEquals(2.0, registry.get("ledgerops.duplicate.financial.effect")
                .functionCounter().count());
    }
}
