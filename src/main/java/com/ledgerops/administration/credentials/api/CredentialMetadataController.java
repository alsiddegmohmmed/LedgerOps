package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialMetadataQuery;
import com.ledgerops.administration.api.CredentialMetadataQueryPort;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@ConditionalOnBean(CredentialMetadataQueryPort.class)
@RequestMapping("/api/v1/tenants/{tenantId}/credentials")
class CredentialMetadataController {

    private final CredentialMetadataQueryPort metadata;

    CredentialMetadataController(CredentialMetadataQueryPort metadata) {
        this.metadata = metadata;
    }

    @GetMapping("/{credentialId}")
    ResponseEntity<CredentialMetadataHttpResponse> find(
            @PathVariable UUID tenantId,
            @PathVariable UUID credentialId,
            HttpServletRequest httpRequest
    ) {
        var authorization = AuthorizedRequestContextRequest.required(httpRequest);
        var result = metadata.find(new CredentialMetadataQuery(
                tenantId,
                credentialId,
                authorization
        ));
        return ResponseEntity.ok(CredentialMetadataHttpResponse.from(result));
    }
}
