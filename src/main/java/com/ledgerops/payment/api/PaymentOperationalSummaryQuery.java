package com.ledgerops.payment.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only facts exposed by Payment for rebuildable Reporting projections.
 *
 * <p>The implementation remains responsible for querying only Payment-owned
 * persistence. Consumers must not infer financial truth from the current
 * Payment status when a durable provider outcome fact is available.</p>
 */
public interface PaymentOperationalSummaryQuery {

    List<PaymentOperationalSummaryPayment> findPayments(
            UUID tenantId,
            Instant fromInclusive,
            Instant toExclusive,
            Set<UUID> merchantIds
    );

    List<PaymentOperationalSummaryOutcome> findDefinitiveProviderOutcomes(
            UUID tenantId,
            Instant fromInclusive,
            Instant toExclusive,
            Set<UUID> merchantIds
    );
}
