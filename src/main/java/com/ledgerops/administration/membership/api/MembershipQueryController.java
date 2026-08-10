package com.ledgerops.administration.membership.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.MembershipNotFoundException;
import com.ledgerops.identity.api.MembershipQueryPort;
import com.ledgerops.identity.api.MembershipReadAuthorization;
import com.ledgerops.identity.api.MembershipResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/memberships")
class MembershipQueryController {

    private final MembershipQueryPort memberships;

    MembershipQueryController(MembershipQueryPort memberships) {
        this.memberships = memberships;
    }

    @GetMapping
    List<MembershipResponse> current(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        return memberships.current(
                tenantId,
                authorization(tenantId, request)
        );
    }

    @GetMapping("/{membershipId}")
    MembershipResponse current(
            @PathVariable UUID tenantId,
            @PathVariable UUID membershipId,
            HttpServletRequest request
    ) {
        try {
            return memberships.current(
                    tenantId,
                    membershipId,
                    authorization(tenantId, request)
            );
        } catch (MembershipNotFoundException exception) {
            throw new AuthorizationResourceNotFoundException();
        }
    }

    private MembershipReadAuthorization authorization(
            UUID tenantId,
            HttpServletRequest request
    ) {
        AuthorizedRequestContext context = AuthorizedRequestContextRequest.required(request);
        if (!context.tenantId().equals(tenantId)) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!context.isHuman() || !context.canReadMemberships()) {
            throw new AuthorizationPermissionDeniedException("tenant:membership-manage");
        }
        return new MembershipReadAuthorization(
                tenantId,
                context.isTenantWide(),
                context.merchantIds()
        );
    }
}
