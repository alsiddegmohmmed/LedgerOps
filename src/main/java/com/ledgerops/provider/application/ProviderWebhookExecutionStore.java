package com.ledgerops.provider.application;

import java.util.Optional;

public interface ProviderWebhookExecutionStore {
    Optional<ProviderWebhookClaim> claimNextWebhook(String leaseOwner);

    ProviderWebhookProcessingOutcome processWebhook(ProviderWebhookClaim claim);
}
