package com.ledgerops.messaging.api;

public record ConsumerFailureResult(int failureCount, boolean dead) {
}
