package com.ledgerops.identity.application;

public final class OutOfScopeMerchantResourceException extends RuntimeException {

    public OutOfScopeMerchantResourceException() {
        super("The requested resource is outside the authorized Merchant scope");
    }
}
