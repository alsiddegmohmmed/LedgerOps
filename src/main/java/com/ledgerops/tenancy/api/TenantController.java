package com.ledgerops.tenancy.api;

import com.ledgerops.tenancy.application.TenantManagementService;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.tenancy.domain.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
class TenantController {

    private final TenantManagementService tenantManagementService;

    TenantController(TenantManagementService tenantManagementService) {
        this.tenantManagementService = tenantManagementService;
    }

    @GetMapping("/{tenantId}")
    TenantResponse getTenant(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        return TenantResponse.from(
                tenantManagementService.getAuthorizedTenant(
                        TenantId.from(tenantId),
                        AuthorizedRequestContextRequest.required(request)
                )
        );
    }

}
