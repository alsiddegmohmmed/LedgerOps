package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProviderManualRetryPort {

    Optional<ProviderRetryAcceleration> accelerateSafeRetry(
            UUID tenantId,
            UUID paymentId,
            Instant now
    );
}
