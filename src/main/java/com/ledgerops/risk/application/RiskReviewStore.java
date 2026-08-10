package com.ledgerops.risk.application;

import com.ledgerops.risk.domain.RiskReview;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface RiskReviewStore {
    RiskReview insertIfAbsent(RiskReview review);
    Optional<RiskReview> findByTenantAndId(UUID tenantId, UUID reviewId);
    Optional<RiskReview> lockByTenantAndId(UUID tenantId, UUID reviewId);
    List<RiskReview> queue(UUID tenantId);

    List<RiskReview> queue(UUID tenantId, Set<UUID> merchantIds);
    boolean update(RiskReview review, long expectedVersion);
}
