package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderRetryAcceleration(
        UUID workId,
        Instant previousDueAt,
        Instant dueAt
) {
}
