package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only Provider health facts exposed to rebuildable Reporting projections. */
public interface ProviderOperationalSummaryQuery {

    Optional<ProviderHealthEvaluation> latestHealthAtOrBefore(
            String providerId,
            Instant asOf
    );

    List<ProviderHealthEvaluation> healthEvaluationsBetween(
            String providerId,
            Instant fromInclusive,
            Instant toExclusive
    );
}
