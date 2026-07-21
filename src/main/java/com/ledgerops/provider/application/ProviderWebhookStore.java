package com.ledgerops.provider.application;

public interface ProviderWebhookStore {
    void recordPlatformRejection(
            ProviderWebhookRequest request, String bodyHash, String reasonCode);

    void recordInvalidAuthenticated(
            ProviderWebhookRequest request,
            ProviderWebhookAuthenticationResult authentication,
            String bodyHash,
            String reasonCode
    );

    ProviderWebhookReceptionOutcome receiveAuthenticated(
            ProviderWebhookRequest request,
            ProviderWebhookAuthenticationResult authentication,
            ProviderWebhookPayload payload,
            String bodyHash
    );
}
