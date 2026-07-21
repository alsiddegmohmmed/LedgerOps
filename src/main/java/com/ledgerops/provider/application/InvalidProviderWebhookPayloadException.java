package com.ledgerops.provider.application;

public final class InvalidProviderWebhookPayloadException extends RuntimeException {
    public InvalidProviderWebhookPayloadException(String message) {
        super(message);
    }

    public InvalidProviderWebhookPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
