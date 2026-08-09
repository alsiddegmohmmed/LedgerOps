package com.ledgerops.identity.api;

import java.util.List;
import java.util.Objects;

public record ServiceCredentialPage(
        List<ServiceCredentialMetadata> items,
        boolean hasNext
) {

    public ServiceCredentialPage {
        items = List.copyOf(Objects.requireNonNull(items, "Credential page items must not be null"));
        if (hasNext && items.isEmpty()) {
            throw new IllegalArgumentException("A non-empty page is required when another page exists");
        }
    }
}
