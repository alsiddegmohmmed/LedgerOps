package com.ledgerops.provider.application;

public enum ProviderWebhookReceptionOutcome {
    UNAUTHORIZED,
    INVALID_PAYLOAD,
    UNMAPPED,
    ACCEPTED,
    DUPLICATE,
    CONFLICT,
    TOO_LARGE
}
