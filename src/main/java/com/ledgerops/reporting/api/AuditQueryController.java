package com.ledgerops.reporting.api;

import com.ledgerops.audit.api.AuditSearchPage;
import com.ledgerops.audit.api.AuditSearchPort;
import com.ledgerops.audit.api.AuditSearchQuery;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/audit")
class AuditQueryController {

    private final AuditSearchPort audit;

    AuditQueryController(AuditSearchPort audit) {
        this.audit = audit;
    }

    @GetMapping
    ResponseEntity<AuditSearchPage> findPage(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String actorIssuer,
            @RequestParam(required = false) String actorSubject,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String correlationId,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest httpRequest
    ) {
        requireTenantWideRead(tenantId, httpRequest);
        return ResponseEntity.ok(audit.findPage(new AuditSearchQuery(
                tenantId,
                text(actorIssuer),
                text(actorSubject),
                text(action),
                text(entity),
                text(entityId),
                instant(from, "from"),
                instant(to, "to"),
                text(result),
                text(correlationId),
                limit,
                cursor)));
    }

    private static void requireTenantWideRead(UUID tenantId, HttpServletRequest request) {
        var authorization = AuthorizedRequestContextRequest.required(request);
        if (!authorization.tenantId().equals(tenantId)) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!authorization.isHuman() || !authorization.canReadAudit()) {
            throw new AuthorizationPermissionDeniedException("audit:read");
        }
        if (!authorization.isTenantWide()) {
            throw new AuthorizationPermissionDeniedException(
                    "audit:read requires Tenant-wide scope because Audit records are not Merchant-owned");
        }
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant", exception);
        }
    }
}
