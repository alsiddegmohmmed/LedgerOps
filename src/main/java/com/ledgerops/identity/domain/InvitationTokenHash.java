package com.ledgerops.identity.domain;

public record InvitationTokenHash(String value) {
    public InvitationTokenHash {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invitation token hash must not be blank");
        }
        value = value.trim();
    }
}
