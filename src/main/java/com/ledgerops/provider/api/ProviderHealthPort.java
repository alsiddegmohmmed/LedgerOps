package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProviderHealthPort {

    ProviderHealthEvaluation evaluate(String providerId, String circuitState, Instant now);

    Optional<ProviderHealthEvaluation> current(String providerId);

    List<ProviderHealthEvaluation> recent(String providerId, int limit);
}
