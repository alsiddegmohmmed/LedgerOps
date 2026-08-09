package com.ledgerops.administration.membership.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.InvitationRevocationPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/memberships/{membershipId}/invitation/revoke")
class InvitationRevocationController {

    private final InvitationRevocationPort revocations;

    InvitationRevocationController(InvitationRevocationPort revocations) {
        this.revocations = revocations;
    }

    @PostMapping
    ResponseEntity<InvitationRevocationHttpResponse> revoke(
            @PathVariable UUID tenantId,
            @PathVariable UUID membershipId,
            @Valid @RequestBody InvitationRevocationHttpRequest request,
            HttpServletRequest httpRequest
    ) {
        var authorization = AuthorizedRequestContextRequest.required(httpRequest);
        var actor = AuthorizedRequestContextRequest.principal(httpRequest);
        var result = revocations.revoke(
                request.toCommand(tenantId, membershipId, authorization, actor));
        return ResponseEntity.ok(InvitationRevocationHttpResponse.from(result));
    }
}
