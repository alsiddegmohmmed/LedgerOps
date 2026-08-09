package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialMetadataPageQuery;
import com.ledgerops.administration.api.CredentialMetadataPageQueryPort;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@ConditionalOnBean(CredentialMetadataPageQueryPort.class)
@RequestMapping("/api/v1/tenants/{tenantId}/credentials")
class CredentialMetadataPageController {

    private final CredentialMetadataPageQueryPort metadata;

    CredentialMetadataPageController(CredentialMetadataPageQueryPort metadata) {
        this.metadata = metadata;
    }

    @GetMapping
    ResponseEntity<CredentialMetadataPageHttpResponse> findPage(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest httpRequest
    ) {
        var authorization = AuthorizedRequestContextRequest.required(httpRequest);
        var result = metadata.findPage(new CredentialMetadataPageQuery(
                tenantId,
                merchantId,
                status,
                limit,
                cursor,
                authorization
        ));
        return ResponseEntity.ok(CredentialMetadataPageHttpResponse.from(result));
    }
}
