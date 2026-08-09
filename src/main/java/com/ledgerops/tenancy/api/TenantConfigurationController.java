package com.ledgerops.tenancy.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.tenancy.application.TenantConfigurationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/configuration")
class TenantConfigurationController {

    private final TenantConfigurationService configurations;
    private final ObjectMapper objectMapper;

    TenantConfigurationController(
            TenantConfigurationService configurations,
            ObjectMapper objectMapper
    ) {
        this.configurations = configurations;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    TenantConfigurationResponse current(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        return TenantConfigurationResponse.from(
                configurations.current(
                        TenantReference.from(tenantId),
                        AuthorizedRequestContextRequest.required(request)
                ),
                objectMapper
        );
    }

    @PutMapping
    ResponseEntity<TenantConfigurationResponse> update(
            @PathVariable UUID tenantId,
            @Valid @RequestBody TenantConfigurationRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal actor = AuthorizedRequestContextRequest.principal(request);
        var configuration = configurations.update(body.toCommand(
                TenantReference.from(tenantId),
                AuthorizedRequestContextRequest.required(request),
                actor,
                objectMapper
        ));
        return ResponseEntity.ok(TenantConfigurationResponse.from(configuration, objectMapper));
    }

}
