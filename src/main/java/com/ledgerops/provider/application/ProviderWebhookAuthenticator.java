package com.ledgerops.provider.application;

public interface ProviderWebhookAuthenticator {
    ProviderWebhookAuthenticationResult authenticate(ProviderWebhookRequest request);
}
