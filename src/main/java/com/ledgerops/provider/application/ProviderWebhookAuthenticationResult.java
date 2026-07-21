package com.ledgerops.provider.application;

public record ProviderWebhookAuthenticationResult(
        boolean authenticated,
        String providerId,
        String providerClientId,
        String reasonCode
) {
    public static ProviderWebhookAuthenticationResult accepted(
            String providerId, String providerClientId) {
        return new ProviderWebhookAuthenticationResult(
                true, providerId, providerClientId, null);
    }

    public static ProviderWebhookAuthenticationResult rejected(String reasonCode) {
        return new ProviderWebhookAuthenticationResult(false, null, null, reasonCode);
    }
}
