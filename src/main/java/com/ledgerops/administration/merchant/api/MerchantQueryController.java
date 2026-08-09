package com.ledgerops.administration.merchant.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.merchant.api.MerchantNotFoundException;
import com.ledgerops.merchant.api.MerchantQueryPort;
import com.ledgerops.merchant.api.MerchantReadAuthorization;
import com.ledgerops.merchant.api.MerchantResponse;
import com.ledgerops.tenancy.api.TenantReference;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/merchants")
class MerchantQueryController {

    private final MerchantQueryPort merchants;

    MerchantQueryController(MerchantQueryPort merchants) {
        this.merchants = merchants;
    }

    @GetMapping
    List<MerchantResponse> current(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        return merchants.current(
                TenantReference.from(tenantId),
                authorization(tenantId, request)
        );
    }

    @GetMapping("/{merchantId}")
    MerchantResponse current(
            @PathVariable UUID tenantId,
            @PathVariable UUID merchantId,
            HttpServletRequest request
    ) {
        try {
            return merchants.current(
                    TenantReference.from(tenantId),
                    merchantId,
                    authorization(tenantId, request)
            );
        } catch (MerchantNotFoundException exception) {
            throw new AuthorizationResourceNotFoundException();
        }
    }

    private MerchantReadAuthorization authorization(
            UUID tenantId,
            HttpServletRequest request
    ) {
        AuthorizedRequestContext context = AuthorizedRequestContextRequest.required(request);
        if (!context.tenantId().equals(tenantId)) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!context.isHuman() || !context.canReadMerchants()) {
            throw new AuthorizationPermissionDeniedException("merchant:read");
        }
        return new MerchantReadAuthorization(
                tenantId,
                context.isTenantWide(),
                context.merchantIds()
        );
    }
}
