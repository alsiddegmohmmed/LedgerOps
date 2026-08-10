package com.ledgerops.provider.api;

import java.util.List;
import java.util.UUID;

public record ProviderPaymentOperations(
        UUID tenantId,
        UUID paymentId,
        List<ProviderWorkOperation> work,
        List<ProviderInteractionOperation> interactions,
        List<ProviderRecoveryOperation> recovery,
        List<ProviderWebhookOperation> webhooks
) {
    public ProviderPaymentOperations {
        work = List.copyOf(work);
        interactions = List.copyOf(interactions);
        recovery = List.copyOf(recovery);
        webhooks = List.copyOf(webhooks);
    }
}
