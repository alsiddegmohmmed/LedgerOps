package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.ReversalId;

import java.util.Optional;
import java.util.UUID;

public interface ReversalProviderResultStore {

    Optional<AcceptedFinalReversalResult> findAcceptedFinalResult(
            UUID tenantId,
            ReversalId reversalId
    );

    void insertAcceptedFinalResult(AcceptedFinalReversalResult result);
}
