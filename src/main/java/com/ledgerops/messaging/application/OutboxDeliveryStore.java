package com.ledgerops.messaging.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxDeliveryStore {
    List<OutboxClaim> claimDue(String owner, Instant now, Duration lease, int limit);

    boolean renew(UUID outboxId, UUID leaseToken, Instant expiresAt);

    boolean markPublished(UUID outboxId, UUID leaseToken, Instant publishedAt);

    boolean markRetryable(UUID outboxId, UUID leaseToken, Instant dueAt, String code, String summary);

    boolean markDead(UUID outboxId, UUID leaseToken, Instant deadAt, String code, String summary);
}
