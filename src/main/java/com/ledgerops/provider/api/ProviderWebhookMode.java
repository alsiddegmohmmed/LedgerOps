package com.ledgerops.provider.api;

public enum ProviderWebhookMode {
    NORMAL,
    DELAYED,
    DUPLICATE,
    MISSING,
    INVALID_SIGNATURE,
    OUT_OF_ORDER
}
