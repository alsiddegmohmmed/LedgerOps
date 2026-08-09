package com.ledgerops.identity.api;

public final class MembershipNotFoundException extends RuntimeException {

    public MembershipNotFoundException() {
        super("Membership is not available");
    }
}
