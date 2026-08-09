package com.ledgerops.administration.api;

import java.util.List;
import java.util.Objects;

public record CredentialMetadataPageResult(
        List<CredentialMetadataResult> items,
        String nextCursor
) {

    public CredentialMetadataPageResult {
        items = List.copyOf(Objects.requireNonNull(items, "Credential page items must not be null"));
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("Next cursor must not be blank");
        }
    }
}
