package com.ledgerops.risk.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
final class ConfiguredRiskReviewSlaPolicy implements RiskReviewSlaPolicy {
    private final int version;
    private final Duration duration;

    ConfiguredRiskReviewSlaPolicy(
            @Value("${ledgerops.risk.review.sla-version:1}") int version,
            @Value("${ledgerops.risk.review.sla-duration:PT24H}") Duration duration
    ) {
        if (version < 1) throw new IllegalArgumentException("Risk review SLA version must be positive");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Risk review SLA duration must be positive");
        }
        this.version = version;
        this.duration = duration;
    }

    @Override
    public int version() { return version; }

    @Override
    public Duration durationFor(int priority) {
        if (priority < 0) throw new IllegalArgumentException("Risk review priority must not be negative");
        return duration;
    }
}
