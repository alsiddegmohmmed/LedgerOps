package com.ledgerops.notification.api;

public enum WebhookEventType {
    PAYMENT_COMPLETED("payment.completed"),
    PAYMENT_FAILED("payment.failed"),
    PAYMENT_REVERSED("payment.reversed"),
    RISK_REVIEW_REQUIRED("risk.review.required"),
    RECONCILIATION_DISCREPANCY_CREATED("reconciliation.discrepancy.created");

    private final String value;

    WebhookEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static WebhookEventType fromValue(String value) {
        for (WebhookEventType type : values()) {
            if (type.value.equals(value)) return type;
        }
        throw new IllegalArgumentException("Unsupported webhook event type: " + value);
    }
}
