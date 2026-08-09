package com.ledgerops.administration.api;

import com.ledgerops.RequestCorrelationFilter;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.tenancy.api.TenantReference;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
class AdministrationController {

    private final TenantActivationPort tenantActivation;
    private final TenantOnboardingPort tenantOnboarding;
    private final TenantLifecyclePort tenantLifecycle;

    AdministrationController(
            TenantActivationPort tenantActivation,
            TenantOnboardingPort tenantOnboarding,
            TenantLifecyclePort tenantLifecycle
    ) {
        this.tenantActivation = tenantActivation;
        this.tenantOnboarding = tenantOnboarding;
        this.tenantLifecycle = tenantLifecycle;
    }

    @PostMapping
    ResponseEntity<TenantOnboardingResult> onboardTenant(
            @Valid @RequestBody TenantOnboardingHttpRequest request,
            HttpServletRequest servletRequest
    ) {
        AuthenticatedPrincipal actor = AuthorizedRequestContextRequest.principal(servletRequest);
        UUID correlationId = correlationId(servletRequest);
        TenantOnboardingResult result = tenantOnboarding.onboard(
                request.toCommand(actor, correlationId, UUID.randomUUID())
        );
        return ResponseEntity.created(
                        org.springframework.web.servlet.support.ServletUriComponentsBuilder
                                .fromCurrentRequest()
                                .path("/{tenantId}")
                                .buildAndExpand(result.tenantId())
                                .toUri()
                )
                .body(result);
    }

    @PostMapping("/{tenantId}/activate")
    ResponseEntity<TenantActivationResult> activateTenant(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal actor = AuthorizedRequestContextRequest.principal(request);
        TenantActivationResult result = tenantActivation.activate(
                new TenantActivationCommand(
                        TenantReference.from(tenantId),
                        actor,
                        correlationId(request),
                        UUID.randomUUID()
                )
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{tenantId}/suspend")
    ResponseEntity<TenantLifecycleResult> suspendTenant(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(tenantLifecycle.suspend(
                lifecycleCommand(tenantId, request)
        ));
    }

    @PostMapping("/{tenantId}/archive")
    ResponseEntity<TenantLifecycleResult> archiveTenant(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(tenantLifecycle.archive(
                lifecycleCommand(tenantId, request)
        ));
    }

    private TenantLifecycleCommand lifecycleCommand(
            UUID tenantId,
            HttpServletRequest request
    ) {
        return new TenantLifecycleCommand(
                TenantReference.from(tenantId),
                AuthorizedRequestContextRequest.principal(request),
                correlationId(request),
                UUID.randomUUID()
        );
    }

    private UUID correlationId(HttpServletRequest request) {
        Object requestCorrelationId = request.getAttribute(RequestCorrelationFilter.CORRELATION_ID);
        String value = requestCorrelationId instanceof String string
                ? string
                : MDC.get(RequestCorrelationFilter.CORRELATION_ID);
        try {
            return value == null ? UUID.randomUUID() : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return UUID.randomUUID();
        }
    }
}
