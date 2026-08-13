package com.ledgerops.reporting.api;

import com.ledgerops.provider.api.ProviderHealthState;

import java.time.Instant;
import java.util.Objects;

public record OperationalSummaryProviderHealth(
        ProviderHealthState currentState,
        ProviderHealthState worstState,
        Instant mostRecentEvaluationAt,
        OperationalSummarySourceLink source
) {

    public OperationalSummaryProviderHealth {
        Objects.requireNonNull(currentState, "Current Provider health state must not be null");
        Objects.requireNonNull(worstState, "Worst Provider health state must not be null");
        Objects.requireNonNull(mostRecentEvaluationAt,
                "Most recent Provider health evaluation time must not be null");
        Objects.requireNonNull(source, "Provider health source must not be null");
    }
}
