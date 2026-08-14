package com.ledgerops.reporting.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative facts used to replace one Tenant's derived Payment timeline.
 *
 * <p>The request deliberately contains facts rather than Payment source
 * entities. Reporting can therefore rebuild from a published source boundary
 * without reading another module's tables.</p>
 */
public record PaymentTimelineRebuildRequest(
        UUID tenantId,
        List<PaymentTimelineEntry> authoritativeFacts
) {

    public PaymentTimelineRebuildRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(authoritativeFacts, "Authoritative facts must not be null");

        LinkedHashMap<UUID, PaymentTimelineEntry> uniqueFacts = new LinkedHashMap<>();
        for (PaymentTimelineEntry fact : authoritativeFacts) {
            Objects.requireNonNull(fact, "Authoritative fact must not be null");
            if (!tenantId.equals(fact.tenantId())) {
                throw new IllegalArgumentException(
                        "Every authoritative fact must belong to the requested Tenant");
            }
            PaymentTimelineEntry previous = uniqueFacts.putIfAbsent(
                    fact.sourceMessageId(), fact);
            if (previous != null && !previous.equals(fact)) {
                throw new IllegalArgumentException(
                        "One source message ID cannot represent conflicting timeline facts");
            }
        }
        authoritativeFacts = List.copyOf(new ArrayList<>(uniqueFacts.values()));
    }
}
