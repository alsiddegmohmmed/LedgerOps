package com.ledgerops.risk.application;

import java.time.Duration;
import java.time.Instant;

public interface RiskReviewSlaPolicy {
    int version();
    Duration durationFor(int priority);

    default Instant dueAt(Instant createdAt, int priority) {
        return createdAt.plus(durationFor(priority));
    }
}
