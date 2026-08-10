package com.ledgerops.notification.application;

public class WebhookEndpointNotFoundException extends RuntimeException {
    public WebhookEndpointNotFoundException() {
        super("The requested webhook endpoint does not exist in the Tenant and Merchant scope");
    }
}
