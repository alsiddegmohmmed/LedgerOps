package com.ledgerops.reporting.infrastructure;

import com.ledgerops.reporting.api.OperationalSummaryFact;
import com.ledgerops.reporting.api.OperationalSummaryMetricCode;
import com.ledgerops.reporting.api.OperationalSummaryProjectionRebuildRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationalSummaryProjectionStoreTests {

    @Test
    void writesFactsBeforeSwitchingTheCurrentGeneration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                eq("SELECT nextval('reporting.operational_projection_generation_seq')"),
                eq(Long.class))).thenReturn(7L);
        when(jdbc.queryForObject(
                eq("SELECT nextval('reporting.projection_event_id_seq')"),
                eq(Long.class))).thenReturn(18428L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        OperationalSummaryProjectionStore store = new OperationalSummaryProjectionStore(jdbc);
        UUID tenantId = UUID.randomUUID();
        store.rebuild(new OperationalSummaryProjectionRebuildRequest(
                tenantId,
                Instant.parse("2026-08-13T02:00:00Z"),
                18427,
                List.of(new OperationalSummaryFact(
                        tenantId,
                        OperationalSummaryMetricCode.PAYMENT_VOLUME,
                        "PAYMENT",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Instant.parse("2026-08-12T10:00:00Z"),
                        new BigDecimal("10.00"),
                        "SAR",
                        null,
                        null,
                        null))));

        var order = inOrder(jdbc);
        order.verify(jdbc).queryForObject(
                eq("SELECT nextval('reporting.operational_projection_generation_seq')"),
                eq(Long.class));
        order.verify(jdbc).update(contains("INSERT INTO reporting.operational_projection_generation"),
                any(Object[].class));
        order.verify(jdbc).update(contains("INSERT INTO reporting.operational_summary_fact"),
                any(Object[].class));
        order.verify(jdbc).queryForObject(
                eq("SELECT nextval('reporting.projection_event_id_seq')"),
                eq(Long.class));
        order.verify(jdbc).update(contains("INSERT INTO reporting.projection_event"),
                any(Object[].class));
        order.verify(jdbc).update(contains("UPDATE reporting.operational_projection_generation"),
                any(Object[].class));
        order.verify(jdbc).update(contains("INSERT INTO reporting.operational_projection_current"),
                any(Object[].class));
    }
}
