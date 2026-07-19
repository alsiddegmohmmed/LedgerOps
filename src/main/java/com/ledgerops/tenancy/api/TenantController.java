package com.ledgerops.tenancy.api;

import com.ledgerops.tenancy.application.TenantManagementService;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
class TenantController {

    private final TenantManagementService tenantManagementService;

    TenantController(TenantManagementService tenantManagementService) {
        this.tenantManagementService = tenantManagementService;
    }

    @PostMapping
    ResponseEntity<TenantResponse> createTenant(
            @Valid @RequestBody CreateTenantRequest request
    ) {
        Tenant tenant = tenantManagementService.createTenant(request.toCommand());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{tenantId}")
                .buildAndExpand(tenant.id().value())
                .toUri();

        return ResponseEntity.created(location)
                .body(TenantResponse.from(tenant));
    }

    @GetMapping("/{tenantId}")
    TenantResponse getTenant(@PathVariable UUID tenantId) {
        return TenantResponse.from(
                tenantManagementService.getTenant(TenantId.from(tenantId))
        );
    }

    @PostMapping("/{tenantId}/activate")
    TenantResponse activateTenant(@PathVariable UUID tenantId) {
        return TenantResponse.from(
                tenantManagementService.activateTenant(TenantId.from(tenantId))
        );
    }

    @PostMapping("/{tenantId}/suspend")
    TenantResponse suspendTenant(@PathVariable UUID tenantId) {
        return TenantResponse.from(
                tenantManagementService.suspendTenant(TenantId.from(tenantId))
        );
    }

    @PostMapping("/{tenantId}/archive")
    TenantResponse archiveTenant(@PathVariable UUID tenantId) {
        return TenantResponse.from(
                tenantManagementService.archiveTenant(TenantId.from(tenantId))
        );
    }
}
