package com.ledgerops.risk.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface RiskReviewPort {

    RiskReviewSnapshot createIfAbsent(RiskReviewCreationRequest request);

    Optional<RiskReviewSnapshot> findByTenantAndId(java.util.UUID tenantId, java.util.UUID reviewId);

    /**
     * Loads the review while holding its database row lock for a caller that
     * already owns the surrounding transaction.
     */
    Optional<RiskReviewSnapshot> lockByTenantAndId(java.util.UUID tenantId, java.util.UUID reviewId);

    List<RiskReviewSnapshot> queue(java.util.UUID tenantId);

    List<RiskReviewSnapshot> queue(java.util.UUID tenantId, Set<java.util.UUID> merchantIds);

    RiskReviewSnapshot assign(RiskReviewAssignmentRequest request);

    RiskReviewSnapshot decide(RiskReviewDecisionRequest request);
}
