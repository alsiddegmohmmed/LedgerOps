package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialAdministrationPort;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@ConditionalOnBean(CredentialAdministrationPort.class)
@RequestMapping("/api/v1/tenants/{tenantId}/credentials")
class CredentialController {

    private final CredentialAdministrationPort administration;

    CredentialController(CredentialAdministrationPort administration) {
        this.administration = administration;
    }

    @PostMapping
    ResponseEntity<CredentialProvisioningHttpResponse> provision(
            @PathVariable UUID tenantId,
            @Valid @RequestBody CredentialProvisioningHttpRequest request,
            HttpServletRequest httpRequest
    ) {
        var authorization = AuthorizedRequestContextRequest.required(httpRequest);
        var actor = AuthorizedRequestContextRequest.principal(httpRequest);
        var result = administration.provision(
                request.toCommand(tenantId, authorization, actor));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CredentialProvisioningHttpResponse.from(result));
    }

    @PostMapping("/{credentialId}/rotate")
    ResponseEntity<CredentialRotationHttpResponse> rotate(
            @PathVariable UUID tenantId,
            @PathVariable UUID credentialId,
            @Valid @RequestBody CredentialActionHttpRequest request,
            HttpServletRequest httpRequest
    ) {
        var authorization = AuthorizedRequestContextRequest.required(httpRequest);
        var actor = AuthorizedRequestContextRequest.principal(httpRequest);
        var result = administration.rotate(
                request.toRotationCommand(tenantId, credentialId, authorization, actor));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CredentialRotationHttpResponse.from(result));
    }

    @PostMapping("/{credentialId}/revoke")
    ResponseEntity<CredentialRevocationHttpResponse> revoke(
            @PathVariable UUID tenantId,
            @PathVariable UUID credentialId,
            @Valid @RequestBody CredentialActionHttpRequest request,
            HttpServletRequest httpRequest
    ) {
        var authorization = AuthorizedRequestContextRequest.required(httpRequest);
        var actor = AuthorizedRequestContextRequest.principal(httpRequest);
        var result = administration.revoke(
                request.toRevocationCommand(tenantId, credentialId, authorization, actor));
        return ResponseEntity.ok(CredentialRevocationHttpResponse.from(result));
    }
}
