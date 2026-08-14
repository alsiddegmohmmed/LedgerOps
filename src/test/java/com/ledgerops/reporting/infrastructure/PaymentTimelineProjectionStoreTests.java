package com.ledgerops.reporting.infrastructure;

import com.ledgerops.reporting.api.PaymentTimelineEntry;
import com.ledgerops.reporting.api.PaymentTimelineRebuildRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentTimelineProjectionStoreTests {

    @Test
    void rebuildReplacesOnlyTheRequestedTenantAndInsertsEachUniqueFact() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        PaymentTimelineProjectionStore store = new PaymentTimelineProjectionStore(jdbc);
        UUID tenantId = UUID.randomUUID();
        PaymentTimelineEntry fact = fact(tenantId, UUID.randomUUID());

        store.rebuild(new PaymentTimelineRebuildRequest(
                tenantId, List.of(fact, fact)));

        verify(jdbc).update(contains("DELETE FROM reporting.payment_timeline_projection"),
                any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO reporting.payment_timeline_projection"),
                any(Object[].class));
    }

    @Test
    void rebuildRejectsFactsFromAnotherTenantBeforeChangingProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new PaymentTimelineRebuildRequest(
                tenantId, List.of(fact(UUID.randomUUID(), UUID.randomUUID()))));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rebuildRejectsConflictingFactsWithTheSameSourceMessageId() {
        UUID tenantId = UUID.randomUUID();
        UUID sourceMessageId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new PaymentTimelineRebuildRequest(
                tenantId,
                List.of(fact(tenantId, sourceMessageId),
                        new PaymentTimelineEntry(
                                sourceMessageId,
                                tenantId,
                                UUID.randomUUID(),
                                null,
                                "PAYMENT",
                                "PaymentLifecycleChanged",
                                UUID.randomUUID(),
                                Instant.parse("2026-08-12T10:00:01Z"),
                                "AUTOMATED",
                                "FAILED",
                                "DECLINED",
                                null,
                                "Payment failed"))));
    }

    private static PaymentTimelineEntry fact(UUID tenantId, UUID sourceMessageId) {
        UUID paymentId = UUID.randomUUID();
        return new PaymentTimelineEntry(
                sourceMessageId,
                tenantId,
                paymentId,
                UUID.randomUUID(),
                "PAYMENT",
                "PaymentLifecycleChanged",
                paymentId,
                Instant.parse("2026-08-12T10:00:00Z"),
                "AUTOMATED",
                "COMPLETED",
                null,
                null,
                "Payment completed");
    }
}
