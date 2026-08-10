package com.ledgerops.reporting.application;

import com.ledgerops.payment.api.PaymentDetailsSnapshot;
import com.ledgerops.reporting.api.PaymentTimelineEntry;

import java.util.UUID;

public interface PaymentTimelineProjector {

    void ensureBaseline(PaymentDetailsSnapshot payment);

    void project(PaymentTimelineEntry entry);

    UUID baselineMessageId(UUID paymentId);
}
