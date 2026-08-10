package com.ledgerops.administration.membership.api;

import com.ledgerops.RequestCorrelationFilter;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.InvitationAdministrationPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/memberships")
class InvitationAdministrationController {

    private final InvitationAdministrationPort administration;

    InvitationAdministrationController(InvitationAdministrationPort administration) {
        this.administration = administration;
    }

    @PostMapping("/invitations")
    ResponseEntity<InvitationAdministrationHttpResponse> create(
            @PathVariable UUID tenantId,
            @Valid @RequestBody InvitationAdministrationHttpRequest request,
            HttpServletRequest servletRequest
    ) {
        var result = administration.create(request.toCommand(
                tenantId,
                null,
                AuthorizedRequestContextRequest.required(servletRequest),
                AuthorizedRequestContextRequest.principal(servletRequest),
                correlationId(servletRequest)
        ));
        return ResponseEntity.status(201)
                .body(InvitationAdministrationHttpResponse.from(result));
    }

    @PostMapping("/{membershipId}/reinvite")
    ResponseEntity<InvitationAdministrationHttpResponse> reinvite(
            @PathVariable UUID tenantId,
            @PathVariable UUID membershipId,
            @Valid @RequestBody InvitationAdministrationHttpRequest request,
            HttpServletRequest servletRequest
    ) {
        var result = administration.reinvite(request.toCommand(
                tenantId,
                membershipId,
                AuthorizedRequestContextRequest.required(servletRequest),
                AuthorizedRequestContextRequest.principal(servletRequest),
                correlationId(servletRequest)
        ));
        return ResponseEntity.status(201)
                .body(InvitationAdministrationHttpResponse.from(result));
    }

    @PutMapping("/{membershipId}/roles")
    ResponseEntity<MembershipRoleMutationHttpResponse> replaceRoles(
            @PathVariable UUID tenantId,
            @PathVariable UUID membershipId,
            @Valid @RequestBody MembershipRoleMutationHttpRequest request,
            HttpServletRequest servletRequest
    ) {
        var result = administration.replaceRoles(request.toCommand(
                tenantId,
                membershipId,
                AuthorizedRequestContextRequest.required(servletRequest),
                AuthorizedRequestContextRequest.principal(servletRequest),
                correlationId(servletRequest)
        ));
        return ResponseEntity.ok(MembershipRoleMutationHttpResponse.from(result));
    }

    private UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestCorrelationFilter.CORRELATION_ID);
        String correlation = value instanceof String string
                ? string
                : MDC.get(RequestCorrelationFilter.CORRELATION_ID);
        try {
            return correlation == null ? UUID.randomUUID() : UUID.fromString(correlation);
        } catch (IllegalArgumentException exception) {
            return UUID.randomUUID();
        }
    }
}
