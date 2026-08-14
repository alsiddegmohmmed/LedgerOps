package com.ledgerops.reporting.api;

/**
 * Rebuilds the derived Payment timeline from published authoritative facts.
 */
public interface PaymentTimelineRebuildPort {

    void rebuild(PaymentTimelineRebuildRequest request);
}
