package com.ledgerops.reporting.api;

import java.util.UUID;

/**
 * Read-only internal boundary for Tenant-scoped Payment-timeline replay.
 *
 * <p>The external SSE controller is deliberately deferred until its complete
 * HTTP and event-payload contract is approved.</p>
 */
public interface PaymentTimelineStreamQuery {

    PaymentTimelineStreamReplay replayAfter(UUID tenantId, long lastEventId);
}
