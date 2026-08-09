package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialMetadataPageResult;

import java.util.List;

record CredentialMetadataPageHttpResponse(
        List<CredentialMetadataHttpResponse> items,
        String nextCursor
) {

    static CredentialMetadataPageHttpResponse from(CredentialMetadataPageResult result) {
        return new CredentialMetadataPageHttpResponse(
                result.items().stream()
                        .map(CredentialMetadataHttpResponse::from)
                        .toList(),
                result.nextCursor()
        );
    }
}
