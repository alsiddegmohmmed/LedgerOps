package com.ledgerops.identity.domain;

import java.util.Locale;
import java.util.Objects;

public record InvitationAcceptance(ApplicationUserId applicationUserId, String verifiedEmail) {
    public InvitationAcceptance {
        Objects.requireNonNull(applicationUserId, "Application user ID must not be null");
        if (verifiedEmail == null || verifiedEmail.isBlank()) {
            throw new IllegalArgumentException("Verified email must not be blank");
        }
        verifiedEmail = verifiedEmail.trim().toLowerCase(Locale.ROOT);
    }
}
