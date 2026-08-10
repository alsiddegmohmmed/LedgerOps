package com.ledgerops.provider.web.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.PlatformAuthorityPort;
import com.ledgerops.provider.api.ProviderHealthEvaluation;
import com.ledgerops.provider.api.ProviderHealthPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class ProviderHealthController {

    private final ProviderHealthPort health;
    private final PlatformAuthorityPort platformAuthority;

    ProviderHealthController(ProviderHealthPort health, PlatformAuthorityPort platformAuthority) {
        this.health = health;
        this.platformAuthority = platformAuthority;
    }

    @GetMapping("/platform/provider/health")
    ResponseEntity<ProviderHealthResponse> platformCurrent(
            @RequestParam(defaultValue = "SIMULATOR") String providerId,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal actor = AuthorizedRequestContextRequest.principal(request);
        platformAuthority.requirePlatformAdmin(actor);
        return ResponseEntity.ok(ProviderHealthResponse.from(
                health.current(providerId).orElseThrow(() ->
                        new IllegalArgumentException("Provider health is not evaluated yet"))));
    }

    @GetMapping("/platform/provider/health/history")
    ResponseEntity<List<ProviderHealthResponse>> platformHistory(
            @RequestParam(defaultValue = "SIMULATOR") String providerId,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request
    ) {
        platformAuthority.requirePlatformAdmin(AuthorizedRequestContextRequest.principal(request));
        return ResponseEntity.ok(health.recent(providerId, limit).stream()
                .map(ProviderHealthResponse::from).toList());
    }

    @GetMapping("/tenants/{tenantId}/provider/health")
    ResponseEntity<ProviderHealthResponse> tenantCurrent(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "SIMULATOR") String providerId,
            HttpServletRequest request
    ) {
        AuthorizedRequestContext context = AuthorizedRequestContextRequest.required(request);
        requireTenantHealthRead(tenantId, context);
        return ResponseEntity.ok(ProviderHealthResponse.from(
                health.current(providerId).orElseThrow(() ->
                        new IllegalArgumentException("Provider health is not evaluated yet"))));
    }

    @GetMapping("/tenants/{tenantId}/provider/health/history")
    ResponseEntity<List<ProviderHealthResponse>> tenantHistory(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "SIMULATOR") String providerId,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request
    ) {
        AuthorizedRequestContext context = AuthorizedRequestContextRequest.required(request);
        requireTenantHealthRead(tenantId, context);
        return ResponseEntity.ok(health.recent(providerId, limit).stream()
                .map(ProviderHealthResponse::from).toList());
    }

    private void requireTenantHealthRead(UUID tenantId, AuthorizedRequestContext context) {
        if (!tenantId.equals(context.tenantId())) {
            throw new com.ledgerops.identity.api.AuthorizationResourceNotFoundException();
        }
        if (!context.canReadProviderHealth()) {
            throw new com.ledgerops.identity.api.AuthorizationPermissionDeniedException(
                    "provider:health-read");
        }
    }
}

record ProviderHealthResponse(
        UUID evaluationId,
        String providerId,
        UUID policyId,
        long policyVersion,
        long healthVersion,
        String state,
        int completedCalls,
        int successfulCommunications,
        int timeoutCount,
        int systemErrorCount,
        long p95LatencyMillis,
        String circuitState,
        Instant windowStartedAt,
        Instant windowEndedAt,
        Instant evaluatedAt
) {
    static ProviderHealthResponse from(ProviderHealthEvaluation evaluation) {
        return new ProviderHealthResponse(
                evaluation.evaluationId(), evaluation.providerId(), evaluation.policyId(),
                evaluation.policyVersion(), evaluation.healthVersion(), evaluation.state().name(),
                evaluation.completedCalls(), evaluation.successfulCommunications(),
                evaluation.timeoutCount(), evaluation.systemErrorCount(), evaluation.p95LatencyMillis(),
                evaluation.circuitState(), evaluation.windowStartedAt(), evaluation.windowEndedAt(),
                evaluation.evaluatedAt());
    }
}
